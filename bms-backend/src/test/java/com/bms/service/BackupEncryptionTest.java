package com.bms.service;

import com.bms.exception.StateConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
}
