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

        assertTrue(Files.exists(dataDir.resolve(DB + ".pre-restore")),
                "the database in use must be kept as a safety copy");
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
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve(DB + ".pre-restore"));
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

        assertFalse(Files.exists(dataDir.resolve(DB + ".pre-restore")),
                "nothing should be rewritten when no restore is staged");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + target);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT invoice FROM sale")) {
            assertTrue(rs.next());
            assertEquals("KEEP-ME", rs.getString(1));
        }
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
}
