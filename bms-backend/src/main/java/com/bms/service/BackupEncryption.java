package com.bms.service;

import com.bms.exception.StateConflictException;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The optional secret that goes on a backup file.
 *
 * <p>A backup is a copy of the whole shop — customer details, product images and
 * every user's password hash — and Google Drive holds whatever it is given for as
 * long as the Google account holds it. If that account is taken over, an
 * unencrypted backup hands the attacker the shop. AES-256-GCM over a key derived
 * from a passphrase the shop types means a stolen Drive file is a sealed one:
 * every piece of it carries its own tag, checked before the restore is allowed to
 * look at the result, so a wrong passphrase or an edited byte fails instead of
 * half-restoring nonsense.
 *
 * <p>The current format, {@code LUMIENC2}, seals the file in chunks of 1 MiB and
 * reads it back the same way. That is not a nicety: with one tag over the whole
 * file, Java's GCM engine holds every ciphertext byte in memory until the last
 * block, so restoring a few hundred megabytes of database could fill the heap of
 * the desktop app and die in the middle. Each chunk is sealed with its own IV —
 * the file's IV with the chunk number mixed into its last four bytes — and its
 * additional authenticated data is the file header plus a flag saying whether it
 * is the last one. So chunks cannot be moved, dropped, or cut off the end of the
 * file without the tag check failing.
 *
 * <p>Layout of a {@code LUMIENC2} file: the eight bytes {@code LUMIENC2}, one byte
 * of key-derivation version, four bytes of PBKDF2 iteration count, a random
 * 16-byte salt, a random 12-byte IV, four bytes of chunk size, then the chunks.
 * Each chunk is its ciphertext followed by its 16-byte tag. The salt, IV and
 * counts are not secrets; they are what stops two backups of the same shop from
 * looking alike and lets a file made years ago open with the work factor it was
 * made with.
 *
 * <p>{@code LUMIENC1} was the same idea with one tag over the whole file and the
 * iteration count fixed in the code. Its reader stays, because a shop's older
 * histories have to stay restorable; it is the only path that needs the whole
 * file in memory.
 *
 * <p>The passphrase is never logged and never stored in the database.
 */
public final class BackupEncryption {

    /** Marks a file as one of ours, so a restore knows to ask for a passphrase. */
    public static final String MAGIC = "LUMIENC1";
    public static final String MAGIC_V2 = "LUMIENC2";
    public static final String ENCRYPTED_SUFFIX = ".enc";

    /** What the shop is told when the passphrase does not open the file. */
    public static final String WRONG_PASSPHRASE_MESSAGE = "Wrong passphrase or damaged file";
    public static final String PASSPHRASE_REQUIRED_MESSAGE =
            "This backup is encrypted. Type the passphrase it was made with.";

    /**
     * What the shop is told when the header is not one this app can work from:
     * a newer key derivation means the app is behind, anything else means the
     * file itself has been hurt. Neither is a passphrase the shop can fix.
     */
    public static final String NEWER_FORMAT_MESSAGE =
            "This backup was made by a newer LumiPOS. Update the app before restoring it.";
    public static final String DAMAGED_HEADER_MESSAGE =
            "This backup file is damaged and cannot be opened.";

    /** The 16 bytes GCM appends to each chunk; a chunk on disk is body plus tag. */
    static final int TAG_BYTES = 16;
    /** What a shop can hold in memory, so the file is walked in pieces instead. */
    static final int DEFAULT_CHUNK_BYTES = 1024 * 1024;
    /** LUMIENC2 header: magic, kdf version, iterations, salt, IV, chunk size. */
    static final int V2_HEADER_BYTES = 8 + 1 + 4 + 16 + 12 + 4;

    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int MAGIC_BYTES_COUNT = 8;
    private static final int ITERATIONS_OFFSET = MAGIC_BYTES_COUNT + 1;
    private static final int SALT_OFFSET = ITERATIONS_OFFSET + 4;
    private static final int IV_OFFSET = SALT_OFFSET + SALT_BYTES;
    private static final int CHUNK_SIZE_OFFSET = IV_OFFSET + IV_BYTES;
    /** 1 = PBKDF2-HMAC-SHA256, the only derivation any LumiPOS has written. */
    private static final byte KDF_VERSION_HMAC_SHA256 = 1;
    /**
     * Current OWASP guidance for PBKDF2-HMAC-SHA256. A backup sits in Drive for
     * years, so the work factor has to be one an attacker cannot simply buy. It
     * is written into every new file, because raising it later must not close
     * the histories already taken.
     */
    private static final int PBKDF2_ITERATIONS = 210_000;
    /**
     * A header is read before the key is derived, so these bounds are what stop a
     * hurt or hostile file from ordering an afternoon of hashing.
     */
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 5_000_000;
    /** One IV per chunk number, so a file runs out of chunks long before it runs out of shop. */
    private static final long MAX_CHUNKS = 1L << 32;
    private static final byte[] MAGIC_V1_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAGIC_V2_BYTES = MAGIC_V2.getBytes(StandardCharsets.US_ASCII);
    private static final int V1_HEADER_BYTES = MAGIC_BYTES_COUNT + SALT_BYTES + IV_BYTES;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupEncryption() {
    }

    /**
     * Whether this file was written by {@link #encrypt}, in either format.
     * Anything else, including every backup taken before encryption existed, is
     * handed back untouched: an older history must stay restorable after this
     * upgrade.
     */
    public static boolean looksEncrypted(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(MAGIC_BYTES_COUNT);
            return Arrays.equals(magic, MAGIC_V1_BYTES) || Arrays.equals(magic, MAGIC_V2_BYTES);
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Writes an encrypted copy of {@code plain} into a new temp file.
     *
     * @param passphrase the caller's own copy: it is not logged and not stored,
     *                   and it stays usable — a BOTH backup seals two files with
     *                   the one secret, so the caller wipes it when both are done
     * @return the temp file, which the caller owns and must delete
     */
    public static Path encrypt(Path plain, char[] passphrase) throws Exception {
        return encrypt(plain, passphrase, PBKDF2_ITERATIONS);
    }

    /**
     * Seals with the work factor written into the file rather than the one the
     * code happens to ask for today, so an old backup still opens after the
     * default is raised.
     */
    static Path encrypt(Path plain, char[] passphrase, int iterations) throws Exception {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        byte[] header = new byte[V2_HEADER_BYTES];
        System.arraycopy(MAGIC_V2_BYTES, 0, header, 0, MAGIC_BYTES_COUNT);
        header[MAGIC_BYTES_COUNT] = KDF_VERSION_HMAC_SHA256;
        putInt(header, ITERATIONS_OFFSET, iterations);
        System.arraycopy(salt, 0, header, SALT_OFFSET, SALT_BYTES);
        System.arraycopy(iv, 0, header, IV_OFFSET, IV_BYTES);
        putInt(header, CHUNK_SIZE_OFFSET, DEFAULT_CHUNK_BYTES);

        SecretKey key = deriveKey(passphrase, salt, iterations);
        Path target = Files.createTempFile("lumipos_backup_", ENCRYPTED_SUFFIX);
        try (InputStream in = Files.newInputStream(plain);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            out.write(header);
            byte[] block = new byte[DEFAULT_CHUNK_BYTES];
            for (long chunk = 0; ; chunk++) {
                int read = in.readNBytes(block, 0, DEFAULT_CHUNK_BYTES);
                // A short read is the end of the file, so the chunk after it says
                // "last" — even when the file ends exactly on a chunk boundary,
                // where the last chunk is empty. Without that marker a truncated
                // file would look like a shorter one the shop still owns.
                boolean last = read < DEFAULT_CHUNK_BYTES;
                out.write(chunk(Cipher.ENCRYPT_MODE, key, header, iv, chunk, last, block, read));
                if (last) {
                    return target;
                }
            }
        } catch (Exception ex) {
            Files.deleteIfExists(target);
            throw ex;
        }
    }

    /**
     * Opens an encrypted file into a temp file that the normal checks can read.
     * Nothing the restore works from is left behind when the file does not open:
     * the output is deleted before the throw.
     *
     * @throws StateConflictException when the passphrase is wrong or the file was
     *                                edited
     */
    public static Path decrypt(Path encrypted, char[] passphrase) throws Exception {
        byte[] magic = new byte[MAGIC_BYTES_COUNT];
        try (InputStream in = Files.newInputStream(encrypted)) {
            if (in.readNBytes(magic, 0, magic.length) != magic.length) {
                throw new IOException(notOurs());
            }
        }
        if (Arrays.equals(magic, MAGIC_V2_BYTES)) {
            return decryptV2(encrypted, passphrase);
        }
        if (Arrays.equals(magic, MAGIC_V1_BYTES)) {
            return decryptV1(encrypted, passphrase);
        }
        throw new IOException(notOurs());
    }

    /**
     * Opens a downloaded backup only when it is one of our encrypted files.
     *
     * @return what the restore should work from — {@code downloaded} itself when
     *         the shop never switched encryption on, otherwise a temp file the
     *         caller must delete
     */
    public static Path openIfNeeded(Path downloaded, char[] passphrase) throws Exception {
        return looksEncrypted(downloaded) ? decrypt(downloaded, passphrase) : downloaded;
    }

    /**
     * Reads the file one chunk at a time and stops at the first tag that does not
     * match, so a 300 MB backup costs the same memory as a 3 MB one.
     */
    private static Path decryptV2(Path encrypted, char[] passphrase) throws Exception {
        byte[] header = new byte[V2_HEADER_BYTES];
        try (InputStream in = Files.newInputStream(encrypted)) {
            if (in.readNBytes(header, 0, header.length) != header.length) {
                throw new StateConflictException(DAMAGED_HEADER_MESSAGE);
            }
        }
        if ((header[MAGIC_BYTES_COUNT] & 0xFF) > (KDF_VERSION_HMAC_SHA256 & 0xFF)) {
            throw new StateConflictException(NEWER_FORMAT_MESSAGE);
        }
        if (header[MAGIC_BYTES_COUNT] != KDF_VERSION_HMAC_SHA256) {
            throw new StateConflictException(DAMAGED_HEADER_MESSAGE);
        }
        int iterations = getInt(header, ITERATIONS_OFFSET);
        int chunkSize = getInt(header, CHUNK_SIZE_OFFSET);
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS
                || chunkSize < 1024 || chunkSize > 64 * 1024 * 1024) {
            throw new StateConflictException(DAMAGED_HEADER_MESSAGE);
        }

        byte[] salt = Arrays.copyOfRange(header, SALT_OFFSET, SALT_OFFSET + SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(header, IV_OFFSET, IV_OFFSET + IV_BYTES);
        SecretKey key = deriveKey(passphrase, salt, iterations);

        Path target = Files.createTempFile("lumipos_opened_", ".plain");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(encrypted));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            in.skipNBytes(header.length);
            byte[] raw = new byte[chunkSize + TAG_BYTES];
            for (long chunk = 0; ; chunk++) {
                int read = in.readNBytes(raw, 0, raw.length);
                if (read < TAG_BYTES) {
                    // Too little for even a tag: the file ended without ever
                    // saying it had, which is a cut file rather than a backup.
                    throw new StateConflictException(WRONG_PASSPHRASE_MESSAGE);
                }
                boolean last = read < raw.length;
                out.write(chunk(Cipher.DECRYPT_MODE, key, header, iv, chunk, last, raw, read));
                if (last) {
                    if (in.read() != -1) {
                        throw new StateConflictException(WRONG_PASSPHRASE_MESSAGE);
                    }
                    return target;
                }
            }
        } catch (Exception ex) {
            Files.deleteIfExists(target);
            throw openFailed(ex);
        }
    }

    /**
     * The original whole-file seal. Kept because a shop's older histories must
     * stay restorable; it holds the file in memory while GCM waits for its one
     * tag at the end, which is exactly what {@code LUMIENC2} was written to stop.
     */
    private static Path decryptV1(Path encrypted, char[] passphrase) throws Exception {
        byte[] header = new byte[V1_HEADER_BYTES];
        try (InputStream in = Files.newInputStream(encrypted)) {
            if (in.readNBytes(header, 0, header.length) != header.length) {
                throw new IOException(notOurs());
            }
        }

        byte[] salt = Arrays.copyOfRange(header, MAGIC_BYTES_COUNT, MAGIC_BYTES_COUNT + SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(header, MAGIC_BYTES_COUNT + SALT_BYTES, V1_HEADER_BYTES);

        Path target = Files.createTempFile("lumipos_opened_", ".plain");
        SecretKey key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            try (InputStream in = Files.newInputStream(encrypted);
                 OutputStream out = Files.newOutputStream(target)) {
                in.skipNBytes(V1_HEADER_BYTES);
                seal(in, out, cipher);
            }
            return target;
        } catch (Exception ex) {
            Files.deleteIfExists(target);
            throw openFailed(ex);
        }
    }

    /**
     * Seals or opens one chunk. The IV moves with the chunk number and the header
     * goes into the additional data with the last-chunk flag, so a chunk only
     * opens where the writer put it and only the file's real ending says it ended.
     */
    private static byte[] chunk(int mode, SecretKey key, byte[] header, byte[] baseIv,
                                long number, boolean last, byte[] input, int length)
            throws GeneralSecurityException {
        if (number >= MAX_CHUNKS) {
            throw new IllegalStateException("This backup is too large to seal with one key.");
        }
        byte[] authenticated = Arrays.copyOf(header, header.length + 1);
        authenticated[header.length] = (byte) (last ? 1 : 0);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, chunkIv(baseIv, number)));
        cipher.updateAAD(authenticated);
        return cipher.doFinal(input, 0, length);
    }

    private static byte[] chunkIv(byte[] baseIv, long number) {
        byte[] iv = baseIv.clone();
        int index = (int) number;
        for (int i = 0; i < 4; i++) {
            iv[IV_BYTES - 4 + i] ^= (byte) (index >>> (8 * (3 - i)));
        }
        return iv;
    }

    private static String notOurs() {
        return "This is not an encrypted LumiPOS backup file.";
    }

    /** What the shop is told when a chunk does not carry the tag it should. */
    private static Exception openFailed(Exception ex) {
        if (ex instanceof StateConflictException) {
            return ex;
        }
        if (ex instanceof AEADBadTagException || ex instanceof BadPaddingException) {
            return new StateConflictException(WRONG_PASSPHRASE_MESSAGE);
        }
        return ex;
    }

    /**
     * Runs the file through the cipher in chunks, because a backup of a busy shop
     * is a database and not a memo. GCM holds its own tag back, so the final
     * {@code doFinal} with no input is what proves the whole file.
     */
    private static void seal(InputStream in, OutputStream out, Cipher cipher) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(cipher.update(buffer, 0, read));
        }
        out.write(cipher.doFinal());
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static int getInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static SecretKey deriveKey(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        if (passphrase == null) {
            throw new StateConflictException(PASSPHRASE_REQUIRED_MESSAGE);
        }
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            byte[] raw = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}
