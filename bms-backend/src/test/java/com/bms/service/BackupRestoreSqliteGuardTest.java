package com.bms.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A restore replaces the shop's entire database, so what it accepts has to be a
 * real LumiPOS backup and not merely a healthy SQLite file. These tests work on
 * actual SQLite files because {@code PRAGMA quick_check} is precisely the gate
 * being replaced.
 */
class BackupRestoreSqliteGuardTest {

    private static final String NOT_LUMIPOS = "That file is a database, but not a LumiPOS backup.";
    private static final Set<String> LUMIPOS_SCHEMA =
            Set.of("users", "roles", "products", "sales", "system_settings", "shop_info");

    /** The tables that make a database a LumiPOS database rather than any database. */
    private static final String[] LUMIPOS_TABLES = {
            "create table roles (id integer primary key, name varchar(255) not null unique)",
            "create table users (id integer primary key, username varchar(255) not null, deleted_at timestamp)",
            "create table products (id integer primary key, name varchar(255))",
            "create table sales (id integer primary key, invoice_number varchar(50))",
            "create table system_settings (id integer primary key, setting_key varchar(255))",
            "create table shop_info (id integer primary key, name varchar(255))",
    };

    private GoogleDriveService drive;
    private DataImportService importer;
    private DataSource liveDb;
    private AuditLogService auditLogService;
    private BackupRestoreService service;

    @BeforeEach
    void setUp() {
        drive = mock(GoogleDriveService.class);
        importer = mock(DataImportService.class);
        liveDb = mock(DataSource.class);
        service = new BackupRestoreService(drive, importer, liveDb);
        auditLogService = mock(AuditLogService.class);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
    }

    private void execute(Path db, String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    /** A database with everything a restore needs, plus whatever the test adds. */
    private Path lumiPosBackup(Path dir, String... extraStatements) throws Exception {
        Path db = dir.resolve("backup.db");
        execute(db, LUMIPOS_TABLES);
        if (extraStatements.length > 0) {
            execute(db, extraStatements);
        }
        return db;
    }

    // ------------------------------------------------------------ what may be restored

    @Test
    void aFileWithNothingInItIsRejected(@TempDir Path dir) throws Exception {
        Path empty = dir.resolve("empty.db");
        Files.write(empty, new byte[0]);

        IOException ex = assertThrows(IOException.class, () -> service.assertUsableSqlite(empty));
        assertEquals(NOT_LUMIPOS, ex.getMessage());
    }

    @Test
    void aDatabaseWithNoTablesIsRejected(@TempDir Path dir) throws Exception {
        // A structurally perfect SQLite file that happens to be empty: quick_check
        // says ok, so only the table check can stop this restore.
        Path db = dir.resolve("no-tables.db");
        execute(db, "create table scratch (id integer primary key)");
        execute(db, "drop table scratch");

        IOException ex = assertThrows(IOException.class, () -> service.assertUsableSqlite(db));
        assertEquals(NOT_LUMIPOS, ex.getMessage());
    }

    @Test
    void aDatabaseFromAnotherProgramIsRejected(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("other-app.db");
        execute(db,
                "create table widgets (id integer primary key, name text)",
                "create table orders (id integer primary key, widget_id integer)",
                "insert into widgets (name) values ('Bolt')");

        IOException ex = assertThrows(IOException.class, () -> service.assertUsableSqlite(db));
        assertEquals(NOT_LUMIPOS, ex.getMessage(),
                "the shop is told it is not a LumiPOS backup, not which table is missing");
    }

    @Test
    void aDatabaseMissingOneRequiredTableIsRejected(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("half-setup.db");
        execute(db,
                "create table roles (id integer primary key)",
                "create table users (id integer primary key, deleted_at timestamp)",
                "create table products (id integer primary key)",
                "create table sales (id integer primary key)",
                "create table system_settings (id integer primary key)",
                "insert into users (id) values (1)");

        IOException ex = assertThrows(IOException.class, () -> service.assertUsableSqlite(db));
        assertEquals(NOT_LUMIPOS, ex.getMessage());
    }

    @Test
    void aBackupWithNobodyLeftToSignInIsRejected(@TempDir Path dir) throws Exception {
        Path db = lumiPosBackup(dir,
                "insert into users (username, deleted_at) values ('old.manager', '2026-01-01 09:00:00')",
                "insert into users (username, deleted_at) values ('old.cashier', '2026-01-01 09:00:00')");

        IOException ex = assertThrows(IOException.class, () -> service.assertUsableSqlite(db));
        assertEquals(NOT_LUMIPOS, ex.getMessage(),
                "every user deleted means the shop could not sign in after the restore");
    }

    @Test
    void aRealBackupIsAccepted(@TempDir Path dir) throws Exception {
        Path db = lumiPosBackup(dir,
                "insert into roles (id, name) values (1, 'ROLE_ADMIN')",
                "insert into users (id, username) values (1, 'owner')",
                "insert into products (id, name) values (1, 'Rice 5kg')",
                "insert into sales (id, invoice_number) values (1, 'INV-000001')");

        assertDoesNotThrow(() -> service.assertUsableSqlite(db));
    }

    @Test
    void aRealBackupLeftInWalModeIsAccepted(@TempDir Path dir) throws Exception {
        // Snapshots are taken with VACUUM INTO from a WAL database, so a downloaded
        // backup can arrive flagged as WAL with no sidecar files of its own. A
        // read-only open cannot handle that, which is not a reason to refuse it.
        Path db = lumiPosBackup(dir, "insert into users (id, username) values (1, 'owner')");
        execute(db, "pragma journal_mode=WAL", "pragma wal_checkpoint(TRUNCATE)");

        assertDoesNotThrow(() -> service.assertUsableSqlite(db));
    }

    @Test
    void aFileThatIsNotADatabaseAtAllStillSaysSo(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("garbage.db");
        Files.write(db, "this is a text file, padded well past the size floor ".repeat(200)
                .getBytes(StandardCharsets.UTF_8));

        IOException ex = assertThrows(IOException.class, () -> service.assertUsableSqlite(db));
        assertTrue(ex.getMessage().contains("could not be opened"), "was: " + ex.getMessage());
    }

    // ------------------------------------------------------- newer than this app

    @Test
    void tablesTheAppHasNeverHeardOfAreReportedButDoNotBlock(@TempDir Path dir) throws Exception {
        Path db = lumiPosBackup(dir,
                "insert into users (id, username) values (1, 'owner')",
                "create table time_machines (id integer primary key)",
                "create table future_sales (id integer primary key)");

        assertDoesNotThrow(() -> service.assertUsableSqlite(db));

        List<String> unknown = service.unknownTables(db, LUMIPOS_SCHEMA);
        assertEquals(2, unknown.size(), "was: " + unknown);
        assertTrue(unknown.contains("time_machines"));
        assertTrue(unknown.contains("future_sales"));
    }

    @Test
    void aBackupFromThisVeryAppReportsNothingUnknown(@TempDir Path dir) throws Exception {
        Path db = lumiPosBackup(dir, "insert into users (id, username) values (1, 'owner')");

        assertTrue(service.unknownTables(db, LUMIPOS_SCHEMA).isEmpty());
    }

    // --------------------------------------------------------------- the staging

    @Test
    void aCheckedBackupIsStagedWithAMarkerAndAnAuditEntry(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        Files.createDirectories(dataDir);
        Path downloaded = lumiPosBackup(dir,
                "insert into users (id, username) values (1, 'owner')");

        BackupRestoreService.StagedDatabase staged =
                service.stageDatabase(downloaded, dataDir, "drive-file-1", 12345L, 7L);

        assertEquals("Checking the database... ok", staged.checkStep());
        assertNull(staged.schemaWarning(), "this backup is from this very app");
        assertTrue(Files.exists(dataDir.resolve("lumipos-restored.db")),
                "the checked database is parked next to the live one for the next start");
        assertTrue(Files.exists(dataDir.resolve(".lumipos-pending-restore")),
                "without the marker SqliteDataDirMigration will not swap anything");

        verify(auditLogService).logAction(eq(7L), eq("DB_RESTORE_STAGED"),
                ArgumentMatchers.contains("drive-file-1"), anyString(), any(), any(), any());
    }

    @Test
    void aRefusedBackupStagesNothingAndIsNotAudited(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        Files.createDirectories(dataDir);
        Path foreign = dir.resolve("foreign.db");
        execute(foreign,
                "create table widgets (id integer primary key, name text)",
                "create table orders (id integer primary key, widget_id integer)",
                "insert into widgets (name) values ('Bolt')");

        IOException ex = assertThrows(IOException.class,
                () -> service.stageDatabase(foreign, dataDir, "drive-file-2", 4096L, 7L));

        assertEquals(NOT_LUMIPOS, ex.getMessage());
        assertFalse(Files.exists(dataDir.resolve("lumipos-restored.db")),
                "a refused backup must not be able to overwrite anything on the next start");
        assertFalse(Files.exists(dataDir.resolve(".lumipos-pending-restore")));
        verify(auditLogService, never()).logAction(any(), anyString(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void aBackupFromANewerVersionIsStagedWithAWarning(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        Files.createDirectories(dataDir);
        Path downloaded = lumiPosBackup(dir,
                "insert into users (id, username) values (1, 'owner')",
                "create table time_machines (id integer primary key)");
        stubLiveTables("users", "roles", "products", "sales", "system_settings", "shop_info");

        BackupRestoreService.StagedDatabase staged =
                service.stageDatabase(downloaded, dataDir, "drive-file-3", 4096L, 7L);

        assertTrue(staged.schemaWarning().contains("time_machines"),
                "the shop must be told what this app does not know: " + staged.schemaWarning());
        assertTrue(Files.exists(dataDir.resolve("lumipos-restored.db")),
                "unknown tables warn, they do not block");
    }

    @Test
    void theCompareAgainstTheLiveDatabaseNeverBlocksARestore(@TempDir Path dir) throws Exception {
        // No live connection at all (H2 test rigs, a pool under pressure): the
        // backup is still a valid restore, it just cannot be compared.
        when(liveDb.getConnection()).thenThrow(new java.sql.SQLException("pool is closed"));
        Path dataDir = dir.resolve("data");
        Files.createDirectories(dataDir);
        Path downloaded = lumiPosBackup(dir, "insert into users (id, username) values (1, 'owner')");

        BackupRestoreService.StagedDatabase staged =
                service.stageDatabase(downloaded, dataDir, "drive-file-4", 4096L, 7L);

        assertNull(staged.schemaWarning());
        assertTrue(Files.exists(dataDir.resolve("lumipos-restored.db")));
    }

    @Test
    void aStagedRestoreFromTheDiskLeavesNoSidecarBehind(@TempDir Path dir) throws Exception {
        // The staged file lives beside the live database, so a -wal of its own
        // would be mistaken for the live database's log.
        Path dataDir = dir.resolve("data");
        Files.createDirectories(dataDir);
        Path downloaded = lumiPosBackup(dir, "insert into users (id, username) values (1, 'owner')");
        execute(downloaded, "pragma journal_mode=WAL",
                "insert into products (id, name) values (9, 'Flour')", "pragma wal_checkpoint(TRUNCATE)");

        service.stageDatabase(downloaded, dataDir, "drive-file-5", 4096L, 7L);

        assertFalse(Files.exists(dataDir.resolve("lumipos-restored.db-wal")));
        assertFalse(Files.exists(dataDir.resolve("lumipos-restored.db-shm")));
    }

    // ------------------------------------------------------------------ helpers

    private void stubLiveTables(String... names) throws Exception {
        Connection live = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet rows = mock(ResultSet.class);
        when(liveDb.getConnection()).thenReturn(live);
        when(live.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(any(), any(), anyString(), any())).thenReturn(rows);
        Iterator<String> remaining = Arrays.asList(names).iterator();
        when(rows.next()).thenAnswer(invocation -> remaining.hasNext());
        when(rows.getString("TABLE_NAME")).thenAnswer(invocation -> remaining.next());
    }
}
