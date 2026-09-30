package com.bms.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the property contract of {@link SqliteDataDirMigration}: which file the
 * application actually opens.
 *
 * <p>The migration originally published the new {@code spring.datasource.url}
 * only on the launch that performed the copy. Because the datasource URL is
 * bound from {@code application-sqlite.yml} on every other launch, those starts
 * silently reopened the legacy file in C:\LumiPOS. The data directory migration
 * therefore did nothing beyond the first run, and a raw restore staged into the
 * data directory could never take effect because the application was reading a
 * different file entirely.
 */
class SqliteDataDirUrlTest {

    private static final String DB = "lumipos.db";
    private static final String MARKER = ".lumipos-migrated";

    private static MockEnvironment sqliteEnvironment(Path dataDir) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("sqlite");
        environment.setProperty("lumipos.data-dir", dataDir.toString());
        return environment;
    }

    private static String urlOf(MockEnvironment environment) {
        return environment.getProperty("spring.datasource.url");
    }

    private static void createDatabase(Path file, String invoice) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sale (id INTEGER PRIMARY KEY, invoice TEXT)");
            s.execute("INSERT INTO sale (invoice) VALUES ('" + invoice + "')");
        }
    }

    /** Appends a sale, leaving the modification time genuinely newer. */
    private static void addSale(Path file, String invoice) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO sale (invoice) VALUES ('" + invoice + "')");
        }
    }

    private static String invoiceIn(Path file) throws Exception {
        return invoicesIn(file).isEmpty() ? null : invoicesIn(file).get(0);
    }

    private static List<String> invoicesIn(Path file) throws Exception {
        List<String> invoices = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT invoice FROM sale ORDER BY id")) {
            while (rs.next()) {
                invoices.add(rs.getString(1));
            }
        }
        return invoices;
    }

    private static void writeMarker(Path dataDir, Path legacy, String fingerprint) throws Exception {
        String marker = "migrated-from=" + legacy.toAbsolutePath() + "\n";
        if (fingerprint != null) {
            marker += "legacy-fingerprint=" + fingerprint + "\n";
        }
        Files.writeString(dataDir.resolve(MARKER), marker);
    }

    private static String fingerprintOf(Path file) throws Exception {
        return Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis();
    }

    @Test
    void freshInstallWithNoLegacyDatabaseStillUsesTheDataDirectory(@TempDir Path dataDir,
                                                                    @TempDir Path legacyDir) throws Exception {
        // A brand new shop has nothing in C:\LumiPOS. The database must be
        // created in the data directory, not back at the legacy path.
        MockEnvironment environment = sqliteEnvironment(dataDir);

        new SqliteDataDirMigration().postProcess(environment, legacyDir);

        String url = urlOf(environment);
        assertNotNull(url, "the datasource URL must always be published for the sqlite profile");
        assertTrue(url.startsWith("jdbc:sqlite:" + dataDir.toAbsolutePath()),
                "fresh installs must open the data directory, but got: " + url);
    }

    @Test
    void alreadyMigratedInstallKeepsUsingTheDataDirectory(@TempDir Path dataDir,
                                                           @TempDir Path legacyDir) throws Exception {
        // The regression itself: target and marker both present, which used to
        // return before the URL was published and send the app back to C:\LumiPOS.
        createDatabase(dataDir.resolve(DB), "INV-DATA");
        writeMarker(dataDir, legacyDir.resolve(DB), null);

        MockEnvironment environment = sqliteEnvironment(dataDir);
        new SqliteDataDirMigration().postProcess(environment, legacyDir);

        String url = urlOf(environment);
        assertNotNull(url, "an already migrated install must still publish the URL");
        assertTrue(url.startsWith("jdbc:sqlite:" + dataDir.toAbsolutePath()),
                "second and later launches must stay on the data directory, but got: " + url);
        assertEquals("INV-DATA", invoiceIn(dataDir.resolve(DB)), "an unchanged legacy file must not be re-copied");
    }

    @Test
    void staleCopyIsRefreshedWhenLegacyFileIsTheLiveDatabase(@TempDir Path dataDir,
                                                             @TempDir Path legacyDir) throws Exception {
        // Shops that ran a build with the early-return bug kept writing to the
        // legacy file, leaving a frozen copy in the data directory. Honouring the
        // marker alone would switch them onto that stale database and lose every
        // sale taken since.
        //
        // Replays the real history: the first launch migrated, then later
        // launches went back to the legacy file and the shop kept trading.
        Path legacy = legacyDir.resolve(DB);
        Path target = dataDir.resolve(DB);

        createDatabase(legacy, "INV-0001");
        writeMarker(dataDir, legacy, fingerprintOf(legacy));
        Files.copy(legacy, target);

        addSale(legacy, "INV-0002");
        addSale(legacy, "INV-0003");

        MockEnvironment environment = sqliteEnvironment(dataDir);
        new SqliteDataDirMigration().postProcess(environment, legacyDir);

        assertEquals(List.of("INV-0001", "INV-0002", "INV-0003"), invoicesIn(target),
                "sales taken while the app was still using the legacy file must survive the switch");
        assertEquals(List.of("INV-0001", "INV-0002", "INV-0003"), invoicesIn(legacy),
                "the legacy file is never deleted");
    }

    @Test
    void touchingTheCopyDoesNotCauseAReCopy(@TempDir Path dataDir,
                                            @TempDir Path legacyDir) throws Exception {
        // The fingerprint is taken from the legacy file, so an antivirus scan or
        // backup tool that touches the data directory copy must not be mistaken
        // for the live database having moved back. Comparing the two files'
        // timestamps would get this wrong: the copy looks newer even though the
        // legacy file is the one that is genuinely live.
        Path legacy = legacyDir.resolve(DB);
        Path target = dataDir.resolve(DB);

        createDatabase(target, "INV-DATA");
        createDatabase(legacy, "INV-DATA");
        writeMarker(dataDir, legacy, fingerprintOf(legacy));

        Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis() + 600_000));

        MockEnvironment environment = sqliteEnvironment(dataDir);
        new SqliteDataDirMigration().postProcess(environment, legacyDir);

        assertEquals("INV-DATA", invoiceIn(target));
        assertTrue(Files.exists(target));
    }

    @Test
    void dataDirectoryIsNotOverwrittenByAnUnrelatedLegacyFile(@TempDir Path dataDir,
                                                              @TempDir Path legacyDir) throws Exception {
        // A shop that deliberately points lumipos.data-dir at another drive must
        // not have that database clobbered by whatever happens to sit in
        // C:\LumiPOS on the machine. The marker names the file we migrated from,
        // so a legacy file it does not refer to is ignored.
        Path target = dataDir.resolve(DB);
        createDatabase(target, "INV-CUSTOM");
        writeMarker(dataDir, legacyDir.resolve("other.db"), "1@1");

        Path legacy = legacyDir.resolve(DB);
        createDatabase(legacy, "INV-UNRELATED");

        MockEnvironment environment = sqliteEnvironment(dataDir);
        new SqliteDataDirMigration().postProcess(environment, legacyDir);

        assertEquals("INV-CUSTOM", invoiceIn(target),
                "an unrelated legacy database must not overwrite the configured data directory");
    }

    @Test
    void deletedDataFileIsRecoveredFromLegacy(@TempDir Path dataDir, @TempDir Path legacyDir) throws Exception {
        // The marker survived but the database is gone (cleaned up by a disk
        // tool, or a failed copy). Falling back to the legacy file is strictly
        // better than starting the shop on an empty database.
        Path legacy = legacyDir.resolve(DB);
        createDatabase(legacy, "INV-RESCUED");
        writeMarker(dataDir, legacy, fingerprintOf(legacy));

        MockEnvironment environment = sqliteEnvironment(dataDir);
        new SqliteDataDirMigration().postProcess(environment, legacyDir);

        assertTrue(Files.exists(dataDir.resolve(DB)), "the database should be restored from the legacy file");
        assertEquals("INV-RESCUED", invoiceIn(dataDir.resolve(DB)));
    }

    @Test
    void existingDataDatabaseIsNeverReCopied(@TempDir Path dataDir, @TempDir Path legacyDir) throws Exception {
        // The steady state: the data directory is the live database, so an
        // untouched legacy copy must never overwrite it.
        Path target = dataDir.resolve(DB);
        createDatabase(target, "INV-LIVE-DATA");

        Path legacy = legacyDir.resolve(DB);
        createDatabase(legacy, "INV-OLD-LEGACY");
        writeMarker(dataDir, legacy, fingerprintOf(legacy));

        MockEnvironment environment = sqliteEnvironment(dataDir);
        new SqliteDataDirMigration().postProcess(environment, legacyDir);

        assertEquals("INV-LIVE-DATA", invoiceIn(target),
                "a legacy file unchanged since the copy must be ignored");
    }

    @Test
    void migrationIsIdempotentAcrossRepeatedLaunches(@TempDir Path dataDir, @TempDir Path legacyDir) throws Exception {
        // Launching repeatedly must not drift: the first start copies, and every
        // later start keeps pointing at the data directory without recopying.
        Path legacy = legacyDir.resolve(DB);
        createDatabase(legacy, "INV-0001");

        for (int launch = 0; launch < 3; launch++) {
            MockEnvironment environment = sqliteEnvironment(dataDir);
            new SqliteDataDirMigration().postProcess(environment, legacyDir);
            assertTrue(urlOf(environment).startsWith("jdbc:sqlite:" + dataDir.toAbsolutePath()),
                    "launch " + launch + " must open the data directory");
            assertEquals("INV-0001", invoiceIn(dataDir.resolve(DB)), "launch " + launch + " must keep the data");
        }
    }
}
