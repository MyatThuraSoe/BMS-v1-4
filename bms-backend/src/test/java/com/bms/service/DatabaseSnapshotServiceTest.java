package com.bms.service;

import com.bms.exception.StateConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The automatic backup on SQLite is this snapshot, so the snapshot has to be the
 * whole database — every table, every row — and must not disturb the file the shop
 * is selling from. Works on real SQLite because a mocked connection would prove
 * nothing about {@code VACUUM INTO}.
 */
class DatabaseSnapshotServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 2, 0, 0);

    private static void execute(Path db, String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    private static long rows(Path db, String table) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long value(Path db, String table, String column) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select " + column + " from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static DataSource sqlite(Path db) {
        return new SimpleDriverDataSource(new org.sqlite.JDBC(), "jdbc:sqlite:" + db);
    }

    @Test
    void theSnapshotCarriesEveryTableAndRow(@TempDir Path dir) throws Exception {
        Path live = dir.resolve("lumipos.db");
        execute(live,
                "create table users (id integer primary key, username text)",
                "insert into users (username) values ('owner')",
                "insert into users (username) values ('cashier')",
                "create table sales (id integer primary key, invoice_number text)",
                "insert into sales (invoice_number) values ('INV-000001')",
                "create table invoice_sequences (id integer primary key, last_number integer)",
                "insert into invoice_sequences (last_number) values (7)");

        DatabaseSnapshotService service =
                new DatabaseSnapshotService(sqlite(live), mock(ShopTimeService.class));
        Path snapshot = service.createSnapshot();

        try {
            assertEquals(2, rows(snapshot, "users"), "a backup without the staff is no backup");
            assertEquals(1, rows(snapshot, "sales"));
            assertEquals(1, rows(snapshot, "invoice_sequences"), "the counter row came along");
            assertEquals(7, value(snapshot, "invoice_sequences", "last_number"),
                    "the invoice counter must come back with the invoices it already gave out");
            assertEquals(2, rows(live, "users"), "taking a snapshot must not disturb the live file");
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    @Test
    void theNameSortsInTheOrderTheSnapshotsWereTaken(@TempDir Path dir) throws Exception {
        DatabaseSnapshotService service =
                new DatabaseSnapshotService(sqlite(dir.resolve("lumipos.db")), mock(ShopTimeService.class));

        assertEquals("LumiPOS_DB_2026-11-17_020000.db", service.snapshotFileName(NOW, false));
        assertEquals("LumiPOS_DB_2026-11-17_020000_manual.db", service.snapshotFileName(NOW, true),
                "a copy the shop asked for is marked before the extension, where retention can read it");
    }

    @Test
    void anEngineWithoutVacuumReportsItselfAndSuggestsTheExport() {
        DataSource h2 = new SimpleDriverDataSource(new org.h2.Driver(), "jdbc:h2:mem:snapshot_test");
        DatabaseSnapshotService service = new DatabaseSnapshotService(h2, mock(ShopTimeService.class));

        assertFalse(service.isSupported(), "H2 has no VACUUM INTO, so the default backup is JSON");

        StateConflictException ex = assertThrows(StateConflictException.class, service::createSnapshot);
        assertTrue(ex.getMessage().contains("JSON"), "was: " + ex.getMessage());
    }

    @Test
    void aWalDatabaseStillSnapshots(@TempDir Path dir) throws Exception {
        // The shop runs in WAL mode; VACUUM INTO reads through SQLite's own
        // snapshot, so no sidecar copying is involved and no write is blocked.
        Path live = dir.resolve("lumipos.db");
        execute(live, "pragma journal_mode=WAL",
                "create table sales (id integer primary key, invoice_number text)",
                "insert into sales (invoice_number) values ('INV-000001')");

        DatabaseSnapshotService service =
                new DatabaseSnapshotService(sqlite(live), mock(ShopTimeService.class));

        Path snapshot = assertDoesNotThrow(service::createSnapshot);
        assertEquals(1, rows(snapshot, "sales"));
        Files.deleteIfExists(snapshot);
    }
}
