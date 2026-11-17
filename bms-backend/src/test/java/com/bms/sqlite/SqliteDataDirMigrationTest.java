package com.bms.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The database migration is the one piece of this app that can permanently
 * destroy a shop's sales history, so it is tested against real SQLite files
 * rather than mocks.
 */
class SqliteDataDirMigrationTest {

    private static final String DB = "lumipos.db";

    /** Creates a small but genuine SQLite database containing one sale. */
    private void createLegacyDatabase(Path dir) throws Exception {
        Path db = dir.resolve(DB);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sale (id INTEGER PRIMARY KEY, invoice TEXT, total REAL)");
            s.execute("INSERT INTO sale (invoice, total) VALUES ('INV-0001', 42.50)");
        }
    }

    private void invoke(SqliteDataDirMigration migration, String method, Class<?>[] types, Object[] args)
            throws Exception {
        Method m = SqliteDataDirMigration.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        try {
            m.invoke(migration, args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception) {
                throw (Exception) ex.getCause();
            }
            throw ex;
        }
    }

    private void migrate(SqliteDataDirMigration migration, Path legacy, Path target, Path dataDir)
            throws Exception {
        invoke(migration, "migrate",
                new Class<?>[]{Path.class, Path.class, Path.class},
                new Object[]{legacy, target, dataDir});
    }

    @Test
    void migratedDatabaseKeepsEveryRowAndWritesMarker(@TempDir Path legacyDir, @TempDir Path dataDir)
            throws Exception {
        createLegacyDatabase(legacyDir);
        Path legacy = legacyDir.resolve(DB);
        Path target = dataDir.resolve(DB);

        migrate(new SqliteDataDirMigration(), legacy, target, dataDir);

        assertTrue(Files.exists(target), "database should be copied to the new location");
        assertTrue(Files.exists(dataDir.resolve(".lumipos-migrated")), "marker should stop a second run");

        // The sale from the legacy file must be readable at the new path.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + target);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT invoice, total FROM sale")) {
            assertTrue(rs.next(), "migrated database should contain the original row");
            assertEquals("INV-0001", rs.getString(1));
            assertEquals(42.50, rs.getDouble(2), 0.001);
        }

        // Source is never deleted: the shop keeps a recoverable copy.
        assertTrue(Files.exists(legacy), "legacy database must not be deleted");
    }

    @Test
    void walSidecarIsCopiedSoRecentSalesAreNotLost(@TempDir Path legacyDir, @TempDir Path dataDir)
            throws Exception {
        createLegacyDatabase(legacyDir);
        Path legacy = legacyDir.resolve(DB);
        Path target = dataDir.resolve(DB);

        // Simulate a crash that left a committed sale sitting only in the -wal
        // file: hold the connection open with autocheckpoint disabled, which is
        // exactly the state a power cut leaves behind.
        try (Connection crashSim = DriverManager.getConnection("jdbc:sqlite:" + legacy)) {
            try (Statement s = crashSim.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA wal_autocheckpoint=0");
                s.execute("INSERT INTO sale (invoice, total) VALUES ('INV-0002', 10.00)");
            }
            assertTrue(Files.exists(legacyDir.resolve(DB + "-wal")),
                    "test setup needs a -wal sidecar holding the uncheckpointed sale");

            migrate(new SqliteDataDirMigration(), legacy, target, dataDir);

            // The -wal file itself is intentionally NOT asserted to still exist:
            // verify() opens the copy, which replays the WAL into the main file
            // and checkpoints it away. The property that matters is that the
            // uncheckpointed sale is readable in the final database.
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + target);
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sale")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "both sales should survive, including the one only in the WAL");
            }
        }
    }

    @Test
    void pendingRestoreReplacesTheDatabaseAndKeepsASafetyCopy(@TempDir Path dataDir) throws Exception {
        // Live database the shop is currently using.
        Path target = dataDir.resolve(DB);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + target);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sale (id INTEGER PRIMARY KEY, invoice TEXT)");
            s.execute("INSERT INTO sale (invoice) VALUES ('OLD-SALE')");
        }

        // Restore downloaded from Google Drive and staged by BackupRestoreService.
        Path staged = dataDir.resolve("lumipos-restored.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + staged);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sale (id INTEGER PRIMARY KEY, invoice TEXT)");
            s.execute("INSERT INTO sale (invoice) VALUES ('RESTORED-SALE')");
        }
        Files.writeString(dataDir.resolve(".lumipos-pending-restore"), "restored-from-drive\n");
        // A stale sidecar must not be able to replay onto the new file.
        Files.writeString(dataDir.resolve(DB + "-wal"), "stale");

        new SqliteDataDirMigration()
                .applyPendingRestore(dataDir, target);

        List<Path> safety = safetyCopies(dataDir);
        assertEquals(1, safety.size(), "the database in use must be kept as one safety copy");
        assertFalse(Files.exists(staged), "the staged file should be consumed");
        assertFalse(Files.exists(dataDir.resolve(".lumipos-pending-restore")), "marker should be cleared");
        assertFalse(Files.exists(dataDir.resolve(DB + "-wal")), "stale -wal must be removed");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + target);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT invoice FROM sale")) {
            assertTrue(rs.next());
            assertEquals("RESTORED-SALE", rs.getString(1), "the restored database should now be live");
        }

        // And the previous data is still recoverable.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + safety.get(0));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT invoice FROM sale")) {
            assertTrue(rs.next());
            assertEquals("OLD-SALE", rs.getString(1), "pre-restore copy must still hold the old data");
        }
    }

    @Test
    void noStagedRestoreLeavesTheDatabaseAlone(@TempDir Path dataDir) throws Exception {
        Path target = dataDir.resolve(DB);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + target);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sale (id INTEGER PRIMARY KEY, invoice TEXT)");
            s.execute("INSERT INTO sale (invoice) VALUES ('KEEP-ME')");
        }

        new SqliteDataDirMigration().applyPendingRestore(dataDir, target);

        assertTrue(safetyCopies(dataDir).isEmpty(),
                "nothing should be rewritten when no restore is staged");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + target);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT invoice FROM sale")) {
            assertTrue(rs.next());
            assertEquals("KEEP-ME", rs.getString(1));
        }
    }

    @Test
    void aSecondRestoreKeepsItsOwnSafetyCopy(@TempDir Path dataDir) throws Exception {
        Path target = dataDir.resolve(DB);
        stage(dataDir, "FIRST-RESTORE");
        writeDatabase(target, "LIVE-BEFORE-ANY-RESTORE");

        new SqliteDataDirMigration().applyPendingRestore(dataDir, target);
        List<Path> afterFirst = safetyCopies(dataDir);
        assertEquals(1, afterFirst.size(), "was: " + afterFirst);

        stage(dataDir, "SECOND-RESTORE");
        new SqliteDataDirMigration().applyPendingRestore(dataDir, target);
        List<Path> afterSecond = safetyCopies(dataDir);

        assertEquals(2, afterSecond.size(), "each restore must keep its own copy: " + afterSecond);
        assertTrue(Files.exists(afterFirst.get(0)), "the first safety copy must survive the second restore");
        assertEquals("LIVE-BEFORE-ANY-RESTORE", singleInvoice(afterFirst.get(0)),
                "the copy taken by the first restore must still hold what was live before it");
        assertEquals("FIRST-RESTORE", singleInvoice(afterSecond.stream()
                        .filter(path -> !path.equals(afterFirst.get(0))).findFirst().orElseThrow()),
                "the second restore must protect the database the first one installed");
    }

    @Test
    void theWalIsFoldedInBeforeTheSafetyCopyIsTaken(@TempDir Path dataDir) throws Exception {
        Path target = dataDir.resolve(DB);
        writeDatabase(target, "CHECKPOINTED-SALE");

        // The state a power cut leaves behind: a committed sale that only exists
        // in -wal, with the database still flagged as WAL.
        try (Connection open = DriverManager.getConnection("jdbc:sqlite:" + target);
             Statement s = open.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA wal_autocheckpoint=0");
            s.execute("INSERT INTO sale (invoice) VALUES ('ONLY-IN-WAL')");
            assertTrue(Files.exists(dataDir.resolve(DB + "-wal")),
                    "test setup needs a -wal sidecar holding the uncheckpointed sale");

            // What copying the main file straight away would have handed the shop.
            Path naive = dataDir.resolve("main-file-only.db");
            Files.copy(target, naive);
            assertEquals(1, countInvoices(naive),
                    "the sale is not in the main file until the log is folded in");

            assertTrue(new SqliteDataDirMigration().checkpointWal(target),
                    "nothing holds the log, so everything in it belongs in the main file");

            Path safety = dataDir.resolve("copy-after-checkpoint.db");
            Files.copy(target, safety);
            assertEquals(2, countInvoices(safety),
                    "the safety copy has to contain the sale that was only in the write-ahead log");
            assertTrue(hasInvoice(safety, "ONLY-IN-WAL"));
        }
    }

    @Test
    void anUnreadableSafetyCopyStopsTheRestoreAndLeavesTheLiveDatabaseAlone(@TempDir Path dataDir)
            throws Exception {
        Path target = dataDir.resolve(DB);
        // The live file is not a database SQLite can read, so the copy taken of it
        // cannot be proved. Without proof the restore has to stop.
        Files.writeString(target, "not a database, and far too short to look like one");
        Path staged = stage(dataDir, "RESTORED-SALE");
        byte[] liveContents = Files.readAllBytes(target);

        Exception ex = assertThrows(Exception.class,
                () -> new SqliteDataDirMigration().applyPendingRestore(dataDir, target));

        assertTrue(ex.getMessage().toLowerCase().contains("safety")
                        || ex.getMessage().toLowerCase().contains("restore"),
                "the shop must be told what stopped it: " + ex.getMessage());
        assertArrayEquals(liveContents, Files.readAllBytes(target),
                "nothing may overwrite a database whose safety copy could not be proved");
        assertTrue(Files.exists(staged), "the restore stays staged for an operator to deal with");
        assertTrue(Files.exists(dataDir.resolve(".lumipos-pending-restore")));
    }

    @Test
    void theFourthRestoreRemovesOnlyTheOldestSafetyCopy(@TempDir Path dataDir) throws Exception {
        Path target = dataDir.resolve(DB);
        for (int i = 1; i <= 4; i++) {
            writeDatabase(target, "SALE-" + i);
            stage(dataDir, "RESTORED");
            new SqliteDataDirMigration().applyPendingRestore(dataDir, target);
        }

        List<Path> remaining = safetyCopies(dataDir);
        assertEquals(3, remaining.size(), "the default is to keep three: " + remaining);
        assertEquals("SALE-2", singleInvoice(remaining.get(0)),
                "the oldest copy is the one that goes, and SALE-1 was the oldest");
        assertEquals("SALE-4", singleInvoice(remaining.get(remaining.size() - 1)),
                "the copy taken by the restore just applied must always survive");
    }

    @Test
    void theNumberOfKeptCopiesComesFromTheSetting(@TempDir Path dataDir) throws Exception {
        Path target = dataDir.resolve(DB);
        for (int i = 1; i <= 3; i++) {
            restoreOnce(dataDir, target, "SALE-" + i, null);
        }
        assertEquals(3, safetyCopies(dataDir).size(), "before the setting is changed");

        // The shop asks for one copy, in the database that is live right now.
        restoreOnce(dataDir, target, "SALE-4", "1");

        List<Path> remaining = safetyCopies(dataDir);
        assertEquals(1, remaining.size(), "was: " + remaining);
        assertEquals("SALE-4", singleInvoice(remaining.get(0)),
                "the copy just taken is the one that must be kept");
    }

    /** One restore applied at startup, optionally with a keep-count setting in the live database. */
    private void restoreOnce(Path dataDir, Path target, String liveInvoice, String keep)
            throws Exception {
        writeDatabase(target, liveInvoice);
        if (keep != null) {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + target);
                 Statement s = c.createStatement()) {
                s.execute("CREATE TABLE system_settings "
                        + "(setting_key TEXT PRIMARY KEY, setting_value TEXT)");
                s.execute("INSERT INTO system_settings (setting_key, setting_value) "
                        + "VALUES ('sqlite.restoreBackup.keep', '" + keep + "')");
            }
        }
        stage(dataDir, "RESTORED");
        new SqliteDataDirMigration().applyPendingRestore(dataDir, target);
    }

    @Test
    void verifyRejectsATruncatedCopy(@TempDir Path legacyDir, @TempDir Path dataDir) throws Exception {
        createLegacyDatabase(legacyDir);
        Path legacy = legacyDir.resolve(DB);
        Path target = dataDir.resolve(DB);

        // Stand in for a copy interrupted by a full disk.
        Files.copy(legacy, target);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(target.toFile(), "rw")) {
            raf.setLength(raf.length() / 2);
        }

        assertThrows(IOException.class,
                () -> invoke(new SqliteDataDirMigration(), "verify",
                        new Class<?>[]{Path.class, Path.class}, new Object[]{legacy, target}),
                "a copy smaller than the source must be rejected");
    }

    @Test
    void verifyRejectsAFileThatIsNotADatabase(@TempDir Path legacyDir, @TempDir Path dataDir) throws Exception {
        createLegacyDatabase(legacyDir);
        Path legacy = legacyDir.resolve(DB);
        Path target = dataDir.resolve(DB);

        // Same size, but the contents are garbage: only PRAGMA quick_check
        // catches this, so it proves the second line of defence works.
        byte[] source = Files.readAllBytes(legacy);
        byte[] corrupt = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            corrupt[i] = (byte) 0xFF;
        }
        Files.write(target, corrupt, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        assertThrows(IOException.class,
                () -> invoke(new SqliteDataDirMigration(), "verify",
                        new Class<?>[]{Path.class, Path.class}, new Object[]{legacy, target}),
                "a corrupt file of the right size must be rejected");
    }

    // ------------------------------------------------------------------ helpers

    /** A live database the shop is selling on, holding one named sale. */
    private void writeDatabase(Path db, String invoice) throws Exception {
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-wal"));
        Files.deleteIfExists(Path.of(db + "-shm"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sale (id INTEGER PRIMARY KEY, invoice TEXT)");
            s.execute("INSERT INTO sale (invoice) VALUES ('" + invoice + "')");
        }
    }

    /** What BackupRestoreService leaves behind: a staged database and its marker. */
    private Path stage(Path dataDir, String invoice) throws Exception {
        Path staged = dataDir.resolve("lumipos-restored.db");
        writeDatabase(staged, invoice);
        Files.writeString(dataDir.resolve(".lumipos-pending-restore"), "restored-from-drive\n");
        return staged;
    }

    /** Safety copies, oldest first, as their timestamps name them. */
    private List<Path> safetyCopies(Path dataDir) throws Exception {
        try (var stream = Files.list(dataDir)) {
            return stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(DB + ".pre-restore-"))
                    .filter(name -> !name.endsWith("-wal") && !name.endsWith("-shm"))
                    .sorted()
                    .map(name -> dataDir.resolve(name))
                    .toList();
        }
    }

    private String singleInvoice(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT invoice FROM sale")) {
            assertTrue(rs.next(), "no sale found in " + db);
            return rs.getString(1);
        }
    }

    private int countInvoices(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sale")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private boolean hasInvoice(Path db, String invoice) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS sale (id INTEGER PRIMARY KEY, invoice TEXT)");
            try (ResultSet rs = s.executeQuery("SELECT invoice FROM sale WHERE invoice = '" + invoice + "'")) {
                return rs.next();
            }
        }
    }
}
