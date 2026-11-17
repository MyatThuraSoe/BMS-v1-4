package com.bms.service;

import com.bms.exception.StateConflictException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * GCM checks its tag over the whole file before the restore is allowed to look at
 * the result, so a wrong passphrase or an edited byte fails instead of
 * half-restoring nonsense.
 *
 * <p>Layout of an encrypted file: the eight bytes {@code LUMIENC1}, a random
 * 16-byte salt, a random 12-byte IV, then the ciphertext with its tag. The salt
 * and IV are not secrets; they are what stops two backups of the same data from
 * looking alike.
 *
 * <p>The passphrase is never logged and never stored in the database.
 */
public final class BackupEncryption {

    /** Marks a file as one of ours, so a restore knows to ask for a passphrase. */
    public static final String MAGIC = "LUMIENC1";
    public static final String ENCRYPTED_SUFFIX = ".enc";

    /** What the shop is told when the passphrase does not open the file. */
    public static final String WRONG_PASSPHRASE_MESSAGE = "Wrong passphrase or damaged file";
    public static final String PASSPHRASE_REQUIRED_MESSAGE =
            "This backup is encrypted. Type the passphrase it was made with.";

    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    /**
     * Current OWASP guidance for PBKDF2-HMAC-SHA256. A backup sits in Drive for
     * years, so the work factor has to be one an attacker cannot simply buy.
     */
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_BYTES = MAGIC_BYTES.length + SALT_BYTES + IV_BYTES;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupEncryption() {
    }

    /**
     * Whether this file was written by {@link #encrypt}. Anything else, including
     * every backup taken before encryption existed, is handed back untouched: an
     * older history must stay restorable after this upgrade.
     */
    public static boolean looksEncrypted(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(MAGIC_BYTES.length), MAGIC_BYTES);
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
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] iv = randomBytes(IV_BYTES);
        Path target = Files.createTempFile("lumipos_backup_", ENCRYPTED_SUFFIX);
        SecretKey key = deriveKey(passphrase, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            try (InputStream in = Files.newInputStream(plain);
                 OutputStream out = Files.newOutputStream(target)) {
                out.write(MAGIC_BYTES);
                out.write(salt);
                out.write(iv);
                seal(in, out, cipher);
            }
            return target;
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
        byte[] header = new byte[HEADER_BYTES];
        try (InputStream in = Files.newInputStream(encrypted)) {
            if (in.readNBytes(header, 0, header.length) != header.length
                    || !Arrays.equals(Arrays.copyOf(header, MAGIC_BYTES.length), MAGIC_BYTES)) {
                throw new IOException("This is not an encrypted LumiPOS backup file.");
            }
        }
        if (passphrase == null) {
            throw new StateConflictException(PASSPHRASE_REQUIRED_MESSAGE);
        }

        byte[] salt = Arrays.copyOfRange(header, MAGIC_BYTES.length, MAGIC_BYTES.length + SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(header, MAGIC_BYTES.length + SALT_BYTES, HEADER_BYTES);

        Path target = Files.createTempFile("lumipos_opened_", ".plain");
        SecretKey key = deriveKey(passphrase, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            try (InputStream in = Files.newInputStream(encrypted);
                 OutputStream out = Files.newOutputStream(target)) {
                in.skipNBytes(HEADER_BYTES);
                seal(in, out, cipher);
            }
            return target;
        } catch (Exception ex) {
            Files.deleteIfExists(target);
            if (ex instanceof javax.crypto.AEADBadTagException
                    || ex instanceof javax.crypto.BadPaddingException) {
                throw new StateConflictException(WRONG_PASSPHRASE_MESSAGE);
            }
            throw ex;
        }
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

    private static SecretKey deriveKey(char[] passphrase, byte[] salt) throws Exception {
        if (passphrase == null) {
            throw new StateConflictException(PASSPHRASE_REQUIRED_MESSAGE);
        }
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            byte[] raw = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}
