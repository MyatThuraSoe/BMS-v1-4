package com.bms.service;

import com.bms.exception.StateConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backup that cannot be opened is worse than no backup at all, so the seal has
 * to be exact: what went in must come out, and anything that does not match —
 * wrong passphrase, edited file, a shop whose history predates encryption — has
 * to be answered in a way the shop can act on.
 */
class BackupEncryptionTest {

    private static final char[] PASSPHRASE = "night-shift-2026".toCharArray();

    private Path plainBackup(Path dir, byte[] content) throws Exception {
        Path plain = dir.resolve("LumiPOS_Backup_2026-11-17_020000.json");
        Files.write(plain, content);
        return plain;
    }

    private byte[] openEncrypted(Path encrypted, char[] passphrase) throws Exception {
        Path opened = BackupEncryption.decrypt(encrypted, passphrase);
        try {
            return Files.readAllBytes(opened);
        } finally {
            Files.deleteIfExists(opened);
        }
    }

    @Test
    void whatWasSealedOpensAsExactlyWhatWentIn(@TempDir Path dir) throws Exception {
        byte[] data = new byte[200_000];
        new Random(7).nextBytes(data);
        Path sealed = BackupEncryption.encrypt(plainBackup(dir, data), PASSPHRASE.clone());

        try {
            assertArrayEquals(data, openEncrypted(sealed, PASSPHRASE.clone()));
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    @Test
    void theFileSaysForItselfThatItIsOneOfOurs(@TempDir Path dir) throws Exception {
        Path sealed = BackupEncryption.encrypt(plainBackup(dir, "{\"data\":{}}".getBytes()), PASSPHRASE.clone());

        try {
            assertTrue(BackupEncryption.looksEncrypted(sealed));
            assertFalse(BackupEncryption.looksEncrypted(
                    Files.createTempFile("not-a-backup", BackupEncryption.ENCRYPTED_SUFFIX)),
                    "a .enc suffix is not a seal; the magic bytes are");
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    @Test
    void theSameShopDataTwiceDoesNotLookTheSameTwice(@TempDir Path dir) throws Exception {
        byte[] data = "{\"data\":{\"products\":[1,2,3]}}".getBytes(StandardCharsets.UTF_8);
        Path first = BackupEncryption.encrypt(plainBackup(dir, data), PASSPHRASE.clone());
        Path second = BackupEncryption.encrypt(
                dir.resolve("LumiPOS_Backup_2026-11-17_020000.json"), PASSPHRASE.clone());

        try {
            assertNotEquals(Arrays.toString(Files.readAllBytes(first)),
                    Arrays.toString(Files.readAllBytes(second)),
                    "someone comparing two of the shop's backups must learn nothing from it");
            assertArrayEquals(data, openEncrypted(second, PASSPHRASE.clone()));
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
        }
    }

    @Test
    void aByteChangedInDriveIsRefusedRatherThanRestored(@TempDir Path dir) throws Exception {
        Path sealed = BackupEncryption.encrypt(
                plainBackup(dir, "{\"data\":{\"customers\":[\"U\"]}}".getBytes()), PASSPHRASE.clone());
        byte[] bytes = Files.readAllBytes(sealed);
        bytes[bytes.length - 20] ^= 0x01;
        Files.write(sealed, bytes);

        try {
            StateConflictException ex = assertThrows(StateConflictException.class,
                    () -> openEncrypted(sealed, PASSPHRASE.clone()));
            assertEquals(BackupEncryption.WRONG_PASSPHRASE_MESSAGE, ex.getMessage());
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    @Test
    void aWrongPassphraseFailsAndDoesNotRepeatTheSecretBack(@TempDir Path dir) throws Exception {
        Path sealed = BackupEncryption.encrypt(
                plainBackup(dir, "{\"data\":{}}".getBytes()), PASSPHRASE.clone());

        try {
            StateConflictException ex = assertThrows(StateConflictException.class,
                    () -> openEncrypted(sealed, "wrong-passphrase".toCharArray()));
            assertEquals(BackupEncryption.WRONG_PASSPHRASE_MESSAGE, ex.getMessage());
            assertFalse(ex.getMessage().contains("wrong-passphrase"),
                    "what the shop is told is never the passphrase it typed");
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    @Test
    void anEncryptedFileWithoutAPassphraseAsksForOneBeforeOpeningIt(@TempDir Path dir) throws Exception {
        Path sealed = BackupEncryption.encrypt(
                plainBackup(dir, "{\"data\":{}}".getBytes()), PASSPHRASE.clone());

        try {
            StateConflictException ex = assertThrows(StateConflictException.class,
                    () -> BackupEncryption.openIfNeeded(sealed, null));
            assertEquals(BackupEncryption.PASSPHRASE_REQUIRED_MESSAGE, ex.getMessage());
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    @Test
    void theBackupsTakenBeforeEncryptionWasSwitchedOnStillRestore(@TempDir Path dir) throws Exception {
        Path plain = plainBackup(dir, "{\"data\":{\"sales\":[1,2]}}".getBytes());

        assertEquals(plain, BackupEncryption.openIfNeeded(plain, null),
                "a plain backup is handed to the restore as it is, passphrase or not");
        assertArrayEquals(Files.readAllBytes(plain),
                Files.readAllBytes(BackupEncryption.openIfNeeded(plain, PASSPHRASE.clone())));
    }

    @Test
    void aFileThatOnlyLooksLikeAnEncryptedOneIsRefused(@TempDir Path dir) throws Exception {
        Path halfWritten = dir.resolve("cut-short.enc");
        Files.write(halfWritten, BackupEncryption.MAGIC.getBytes(StandardCharsets.US_ASCII));

        assertThrows(Exception.class, () -> BackupEncryption.decrypt(halfWritten, PASSPHRASE.clone()));
    }

    @Test
    void theNameOfASealedBackupSaysSo(@TempDir Path dir) throws Exception {
        Path sealed = BackupEncryption.encrypt(
                plainBackup(dir, "{\"data\":{}}".getBytes()), PASSPHRASE.clone());

        try {
            assertTrue(sealed.getFileName().toString().endsWith(BackupEncryption.ENCRYPTED_SUFFIX));
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    // --- V1: the sealed file is written and read in chunks, so its size is not a
    // --- limit on the memory the restore needs. Every one of these tests is about
    // --- a boundary the chunked format can get wrong.

    private static final int CHUNK = BackupEncryption.DEFAULT_CHUNK_BYTES;
    private static final int HEADER = BackupEncryption.V2_HEADER_BYTES;
    private static final int ON_DISK = CHUNK + BackupEncryption.TAG_BYTES;

    private static byte[] bytes(int length) {
        byte[] data = new byte[length];
        new Random(11).nextBytes(data);
        return data;
    }

    private static int bigEndianAt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static String magicOf(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(8), StandardCharsets.US_ASCII);
        }
    }

    private Path legacyFixture(Path dir) throws Exception {
        Path copy = dir.resolve(LEGACY_FIXTURE_NAME);
        try (InputStream in = getClass().getResourceAsStream("/legacy/" + LEGACY_FIXTURE_NAME)) {
            Files.copy(Objects.requireNonNull(in, "the LUMIENC1 fixture is missing from test resources"), copy);
        }
        return copy;
    }

    private static final String LEGACY_FIXTURE_NAME = "LumiPOS_Backup_2026-11-17_020000.json.enc";
    private static final String LEGACY_FIXTURE_PLAIN =
            "{\"data\":{\"products\":[{\"sku\":\"OLD-1\",\"name\":\"House Tea\"}]}}";

    @Test
    void aSealedFileIsOneOfOursInBothFormats(@TempDir Path dir) throws Exception {
        Path sealed = BackupEncryption.encrypt(plainBackup(dir, bytes(1)), PASSPHRASE.clone());

        try {
            assertEquals(BackupEncryption.MAGIC_V2, magicOf(sealed),
                    "new backups use the chunked format");
            assertEquals(BackupEncryption.MAGIC, magicOf(legacyFixture(dir)),
                    "the old format keeps its own magic so it can still be recognised");
            assertTrue(BackupEncryption.looksEncrypted(sealed));
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    @Test
    void everySizeAroundAChunkBoundaryOpensAsExactlyWhatItWas(@TempDir Path dir) throws Exception {
        int[] sizes = {0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 5 * CHUNK};

        for (int size : sizes) {
            byte[] data = bytes(size);
            Path sealed = BackupEncryption.encrypt(plainBackup(dir, data), PASSPHRASE.clone());
            try {
                assertArrayEquals(data, openEncrypted(sealed, PASSPHRASE.clone()),
                        "a file of " + size + " bytes must survive the chunking");
            } finally {
                Files.deleteIfExists(sealed);
            }
        }
    }

    @Test
    void aByteChangedAnywhereInTheFileIsRefused(@TempDir Path dir) throws Exception {
        byte[] data = bytes(3 * CHUNK + 17);
        Path sealed = BackupEncryption.encrypt(plainBackup(dir, data), PASSPHRASE.clone());

        // A salt byte in the header, a byte in the middle of the file, and a byte in
        // the last chunk: one wrong byte anywhere must stop the whole restore.
        int[] offsets = {20, HEADER + 2 * CHUNK + 5, (int) Files.size(sealed) - 5};
        for (int offset : offsets) {
            byte[] tampered = Files.readAllBytes(sealed);
            tampered[offset] ^= 0x01;
            Path file = dir.resolve("tampered_" + offset + BackupEncryption.ENCRYPTED_SUFFIX);
            Files.write(file, tampered);

            StateConflictException ex = assertThrows(StateConflictException.class,
                    () -> openEncrypted(file, PASSPHRASE.clone()), "offset " + offset);
            assertEquals(BackupEncryption.WRONG_PASSPHRASE_MESSAGE, ex.getMessage());
        }

        Files.deleteIfExists(sealed);
    }

    @Test
    void twoChunksSwappedAroundDoNotOpen(@TempDir Path dir) throws Exception {
        byte[] data = bytes(3 * CHUNK + 17);
        Path sealed = BackupEncryption.encrypt(plainBackup(dir, data), PASSPHRASE.clone());
        byte[] bytes = Files.readAllBytes(sealed);
        byte[] swapped = bytes.clone();
        System.arraycopy(bytes, HEADER + ON_DISK, swapped, HEADER, ON_DISK);
        System.arraycopy(bytes, HEADER, swapped, HEADER + ON_DISK, ON_DISK);
        Path shuffled = dir.resolve("shuffled.enc");
        Files.write(shuffled, swapped);

        try {
            StateConflictException ex = assertThrows(StateConflictException.class,
                    () -> openEncrypted(shuffled, PASSPHRASE.clone()),
                    "moving a chunk somewhere else in the shop's own file is still an edited file");
            assertEquals(BackupEncryption.WRONG_PASSPHRASE_MESSAGE, ex.getMessage());
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    @Test
    void aFileCutShortWhereAChunkEndsIsRefused(@TempDir Path dir) throws Exception {
        byte[] data = bytes(3 * CHUNK + 17);
        Path sealed = BackupEncryption.encrypt(plainBackup(dir, data), PASSPHRASE.clone());
        Path cut = dir.resolve("cut.enc");
        Files.write(cut, Arrays.copyOf(Files.readAllBytes(sealed), HEADER + 2 * ON_DISK));

        try {
            StateConflictException ex = assertThrows(StateConflictException.class,
                    () -> openEncrypted(cut, PASSPHRASE.clone()),
                    "a file that never says it ended is a damaged one, not a shorter backup");
            assertEquals(BackupEncryption.WRONG_PASSPHRASE_MESSAGE, ex.getMessage());
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    @Test
    void theWorkFactorWrittenInTheHeaderIsTheOneUsedToOpenTheFile(@TempDir Path dir) throws Exception {
        byte[] data = "{\"data\":{\"sales\":[1,2,3]}}".getBytes(StandardCharsets.UTF_8);
        Path sealed = BackupEncryption.encrypt(plainBackup(dir, data), PASSPHRASE.clone(), 100_000);

        try {
            assertEquals(100_000, bigEndianAt(Files.readAllBytes(sealed), 9),
                    "the header carries the count, not the code as it happens to be written today");
            assertArrayEquals(data, openEncrypted(sealed, PASSPHRASE.clone()));
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    @Test
    void aHeaderAskingForAnImpossibleNumberOfKeyChecksIsRefusedRatherThanAttempted(@TempDir Path dir)
            throws Exception {
        for (int iterations : new int[]{99_999, 5_000_001}) {
            Path sealed = BackupEncryption.encrypt(plainBackup(dir, bytes(16)), PASSPHRASE.clone(), iterations);

            try {
                assertThrows(StateConflictException.class, () -> openEncrypted(sealed, PASSPHRASE.clone()),
                        iterations + " key checks is not something the app should be pushed into");
            } finally {
                Files.deleteIfExists(sealed);
            }
        }
    }

    @Test
    void aFileSealedByANewerKeyDerivationAsksForANewerApp(@TempDir Path dir) throws Exception {
        Path sealed = BackupEncryption.encrypt(plainBackup(dir, bytes(16)), PASSPHRASE.clone());
        byte[] bytes = Files.readAllBytes(sealed);
        bytes[8] = 2;
        Path future = dir.resolve("from-a-newer-lumipos.enc");
        Files.write(future, bytes);

        try {
            StateConflictException ex = assertThrows(StateConflictException.class,
                    () -> openEncrypted(future, PASSPHRASE.clone()),
                    "the shop must be told to update, not that it mistyped its passphrase");
            assertEquals(BackupEncryption.NEWER_FORMAT_MESSAGE, ex.getMessage());
        } finally {
            Files.deleteIfExists(sealed);
        }
    }

    @Test
    void theBackupsSealedInTheOldFormatStillOpen(@TempDir Path dir) throws Exception {
        Path old = legacyFixture(dir);

        assertEquals(BackupEncryption.MAGIC, magicOf(old));
        assertArrayEquals(LEGACY_FIXTURE_PLAIN.getBytes(StandardCharsets.UTF_8),
                openEncrypted(old, PASSPHRASE.clone()),
                "backups taken before the chunked format must never stop being restorable");
    }

}
