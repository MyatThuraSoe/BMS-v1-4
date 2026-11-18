package com.bms.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A busy shop's database runs to a few hundred megabytes, and the old seal held
 * every byte of it in memory until AES-GCM produced its one tag at the very end,
 * so the desktop app could run out of heap halfway through a restore. The sealed
 * file is therefore written and opened in chunks, and this test says so in the
 * only way that counts: a 300 MB shop database in and out inside 128 MB of heap,
 * and the copy that comes out still opens as the database it was.
 *
 * <p>It runs in its own JVM fork — see the memory-limited-backup execution in
 * pom.xml — because passing on the suite's normal heap would prove nothing.
 */
class BackupEncryptionMemoryTest {

    private static final char[] PASSPHRASE = "night-shift-2026".toCharArray();
    private static final int MEBIBYTE = 1024 * 1024;
    private static final long SIZE = 300L * MEBIBYTE;

    @Test
    void aThreeHundredMegabyteBackupSealsAndOpensInsideASmallHeap(@TempDir Path dir) throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() < 200L * MEBIBYTE,
                "this test only means something on a small heap; run it in the memory-limited fork");

        Path plain = dir.resolve("LumiPOS_DB_2026-11-17_020000.db");
        long rows = writeShopSizedDatabase(plain);

        Path sealed = null;
        Path opened = null;
        try {
            sealed = BackupEncryption.encrypt(plain, PASSPHRASE.clone());
            opened = BackupEncryption.decrypt(sealed, PASSPHRASE.clone());

            assertEquals(Files.size(plain), Files.size(opened), "every byte of the shop came back");
            assertEquals(digest(plain), digest(opened));

            restoreIntoADatabaseAndReadItBack(opened, dir.resolve("bms_db"), rows);
        } finally {
            Files.deleteIfExists(plain);
            Files.deleteIfExists(sealed);
            Files.deleteIfExists(opened);
        }
    }

    /**
     * What a restore ends up doing: the decrypted file becomes the database the app
     * opens, so the ledger has to survive the trip rather than merely match on bytes.
     */
    private void restoreIntoADatabaseAndReadItBack(Path decrypted, Path database, long rows) throws Exception {
        Files.copy(decrypted, database);

        assertEquals("ok", integrityCheckOf(database));
        assertEquals(rows, readTheLedger(database), "the rows written before the seal are the rows after it");
    }

    /** A SQLite shop database of at least {@link #SIZE} bytes, returning the rows it holds. */
    private long writeShopSizedDatabase(Path file) throws Exception {
        String payload = "x".repeat(900);
        long rows = 0;
        try (Connection shop = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            shop.setAutoCommit(false);
            try (Statement create = shop.createStatement()) {
                create.execute("create table sales (id integer primary key, receipt_no text, payload text)");
            }
            try (PreparedStatement insert = shop
                    .prepareStatement("insert into sales (receipt_no, payload) values (?, ?)")) {
                while (Files.size(file) < SIZE) {
                    for (int batch = 0; batch < 1000; batch++) {
                        rows++;
                        insert.setString(1, "R" + rows);
                        insert.setString(2, payload);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                    shop.commit();
                }
            }
        }
        assertTrue(Files.size(file) >= SIZE, "the shop's database has to be the size that broke the old seal");
        return rows;
    }

    private String integrityCheckOf(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("pragma integrity_check")) {
            return result.next() ? result.getString(1) : "no answer";
        }
    }

    private long readTheLedger(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from sales")) {
            return result.next() ? result.getLong(1) : -1L;
        }
    }

    /** A checksum that never holds more of the file in memory than one buffer. */
    private String digest(Path file) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                sha.update(buffer, 0, read);
            }
        }
        return Arrays.toString(sha.digest());
    }
}
