package com.bms.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backup Drive truncated halfway is worse than no backup: the shop believes it
 * can be restored and finds out on the worst day of its life that the file is
 * useless. These tests pin the comparison that decides whether Drive's copy is
 * trusted, including the case where Drive refuses to publish a checksum at all.
 */
class GoogleDriveUploadVerificationTest {

    private static final String NAME = "LumiPOS_DB_2026-11-17_020000.db";
    private static final String MD5 = "0123abcd4567ef890123abcd4567ef89";

    @Test
    void anExactCopyIsAccepted() {
        assertNull(GoogleDriveService.verificationFailure(NAME, 4096, MD5, 4096L, MD5));
    }

    @Test
    void theChecksumIsCaseInsensitive() {
        assertNull(GoogleDriveService.verificationFailure(NAME, 4096, MD5, 4096L, MD5.toUpperCase()));
    }

    @Test
    void aTruncatedCopyIsRejected() {
        String problem = GoogleDriveService.verificationFailure(NAME, 4096, MD5, 12L, null);

        assertTrue(problem.contains("12") && problem.contains("4096"), "both numbers are quoted: " + problem);
        assertTrue(problem.contains(NAME), "the shop is told which file: " + problem);
    }

    @Test
    void aCorruptedCopyIsRejected() {
        String problem = GoogleDriveService.verificationFailure(NAME, 4096, MD5, 4096L,
                "9fe87654dcba321098fe7654dcba3210");

        assertTrue(problem.contains("does not match"), "was: " + problem);
        assertTrue(problem.contains("discarded"), "the shop is told it was removed: " + problem);
    }

    @Test
    void aSizeMatchWithoutAChecksumIsTrusted() {
        // Drive stops publishing md5Checksum for large files. Rejecting them would
        // deny big shops their backup outright.
        assertNull(GoogleDriveService.verificationFailure(NAME, 4096, MD5, 4096L, null));
        assertNull(GoogleDriveService.verificationFailure(NAME, 4096, MD5, 4096L, "  "));
    }

    @Test
    void aMissingRemoteSizeIsJudgedOnTheChecksumAlone() {
        assertNull(GoogleDriveService.verificationFailure(NAME, 4096, MD5, null, MD5));
        assertTrue(GoogleDriveService.verificationFailure(NAME, 4096, MD5, null, "deadbeef") != null);
    }

    @Test
    void theChecksumIsOfTheFileAsSent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("backup.db");
        Files.write(file, "hello".getBytes());

        assertEquals("5d41402abc4b2a76b9719d911017c592", GoogleDriveService.md5Of(file.toFile()));
    }
}
