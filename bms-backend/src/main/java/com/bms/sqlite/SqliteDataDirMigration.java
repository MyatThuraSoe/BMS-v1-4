package com.bms.sqlite;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moves the SQLite database out of {@code C:\LumiPOS} into the per-user data
 * directory ({@code %LOCALAPPDATA%\LumiPOS\data}) and does it exactly once.
 *
 * <p>Why this runs as an {@link EnvironmentPostProcessor} rather than an
 * {@code ApplicationRunner}: the DataSource opens (and therefore creates) the
 * SQLite file during context refresh. By the time an ApplicationRunner fires,
 * an empty database would already exist at the new path and any copy step
 * afterwards would be too late. The directory has to exist before the first
 * connection, and the decision has to be published to the environment before
 * the {@code spring.datasource.url} property is bound.
 *
 * <p>Safety rules:
 * <ul>
 *   <li>The legacy file is <b>copied, never moved or deleted</b>. A copy that
 *       fails verification aborts startup instead of silently opening an empty
 *       database, because an empty database is far more dangerous than a failed
 *       start: the shop would think all sales were gone and start re-keying.</li>
 *   <li>{@code lumipos.db-wal} and {@code lumipos.db-shm} travel with the main
 *       file. A database left in WAL mode by a crash can hold committed
 *       transactions that exist only in the -wal file; copying the main file
 *       alone would silently lose the most recent sales.</li>
 *   <li>A marker file records that the copy happened, so a later start does not
 *       repeat it and cannot clobber the live database with a stale legacy one.</li>
 *   <li>Every staged restore names its safety copy with a time stamp, so a second
 *       restore cannot destroy the copy the first one made, and the number kept is
 *       capped by {@code sqlite.restoreBackup.keep} so the data folder does not
 *       grow by the size of the database every time the shop restores.</li>
 *   <li>Startup is refused if the copy cannot be verified, leaving the legacy
 *       data untouched for the shop owner to recover by hand.</li>
 * </ul>
 *
 * <p>Only active on the {@code sqlite} profile. MySQL and H2 installs are
 * untouched.
 */
public class SqliteDataDirMigration implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "lumipos-sqlite-datadir";
    private static final String DB_FILE_NAME = "lumipos.db";
    private static final String MARKER_FILE_NAME = ".lumipos-migrated";
    private static final String PENDING_RESTORE_FILE = "lumipos-restored.db";
    private static final String PENDING_RESTORE_MARKER = ".lumipos-pending-restore";
    private static final String LEGACY_DIR = "C:/LumiPOS";

    /** Safety copy names have to sort the way they sort by time, so the width is fixed. */
    private static final DateTimeFormatter SAFETY_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String RESTORE_KEEP_SETTING = "sqlite.restoreBackup.keep";
    private static final int DEFAULT_RESTORE_BACKUPS_KEPT = 3;
    private static final Log log = org.apache.commons.logging.LogFactory.getLog(SqliteDataDirMigration.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("sqlite"))) {
            return;
        }
        postProcess(environment, Paths.get(LEGACY_DIR));
    }

    /**
     * The legacy directory is a parameter so the test suite can exercise a real
     * migration without reading or copying the machine's actual C:\LumiPOS.
     */
    void postProcess(ConfigurableEnvironment environment, Path legacyDir) {
        try {
            Path targetDir = resolveDataDir(environment);
            Files.createDirectories(targetDir);

            Path target = targetDir.resolve(DB_FILE_NAME);
            Path marker = targetDir.resolve(MARKER_FILE_NAME);
            Path legacy = legacyDir.resolve(DB_FILE_NAME);

            // A raw database restored from Google Drive is staged next to the
            // live file by BackupRestoreService and swapped in here, because the
            // DataSource holds the live file open for the whole session and
            // replacing it underneath a running JVM corrupts it.
            applyPendingRestore(targetDir, target);

            if (shouldMigrate(legacy, target, marker)) {
                migrate(legacy, target, targetDir);
            }

            // Unconditional, and it must stay that way. Publishing the URL only
            // when a migration happened made the very first launch move the
            // database and every later launch fall back to the legacy path in
            // application-sqlite.yml, so the app quietly kept using
            // C:\LumiPOS forever. That also made staged restores look like they
            // did nothing, because they were applied to a file the app never
            // opened. The URL is now the single source of truth for which file
            // this installation uses.
            publishDatasourceUrl(environment, target);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "LumiPOS could not move the database to the user data directory. "
                            + "Startup is stopped so no data is lost. "
                            + "Move the old database manually to %LOCALAPPDATA%\\LumiPOS\\data and try again. "
                            + "Cause: " + ex.getMessage(), ex);
        }
    }

    /**
     * Decides whether the legacy database still has to be copied.
     *
     * <p>The "target already exists" case is not a simple skip. Earlier builds
     * published the new URL only on the launch that performed the copy, so shops
     * that started one of those builds went on writing to the legacy file while
     * the copy in the data directory sat frozen. Honouring the marker alone
     * would switch those shops onto a stale database and lose every sale taken
     * in the meantime.
     *
     * <p>Whether the legacy file has moved on is judged by comparing it against
     * the size and modification time recorded in the marker at migration time,
     * not by comparing it to the data directory copy. The copy's own timestamp
     * is worthless as a signal: antivirus scans, backup tools, or merely opening
     * the file all touch it without the database changing.
     *
     * <p>The marker names the file that was copied, so an unrelated
     * {@code C:\LumiPOS} can never overwrite a database a shop deliberately keeps
     * somewhere else.
     */
    private boolean shouldMigrate(Path legacy, Path target, Path marker) throws IOException {
        if (!Files.exists(marker)) {
            return Files.exists(legacy) && !Files.exists(target);
        }

        Optional<Path> migratedFrom = markerMigratedFrom(marker);
        if (migratedFrom.isEmpty() || !migratedFrom.get().equals(legacy.toAbsolutePath())) {
            // The marker refers to a different source, so it says nothing about
            // the legacy file in front of us. Leave the data directory alone.
            return false;
        }
        if (!Files.exists(legacy)) {
            return false;
        }
        if (!Files.exists(target)) {
            // Marker says we moved before but the file is gone. Whatever is at
            // the legacy path is all we have, so take it rather than start empty.
            return true;
        }
        if (markerLegacyFingerprint(marker).isEmpty()) {
            // Marker predates the recorded fingerprint. Fall back to comparing
            // the two files, which is weaker but still beats ignoring the change.
            return Files.getLastModifiedTime(legacy).toMillis()
                    > Files.getLastModifiedTime(target).toMillis();
        }
        return !markerLegacyFingerprint(marker).get().equals(fingerprint(legacy));
    }

    /** Size and last-modified time, as recorded when the copy was taken. */
    private static String fingerprint(Path file) throws IOException {
        return Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis();
    }

    private Optional<Path> markerMigratedFrom(Path marker) throws IOException {
        return markerValue(marker, "migrated-from=").map(value -> {
            try {
                return Paths.get(value).toAbsolutePath();
            } catch (InvalidPathException ex) {
                return null;
            }
        });
    }

    private Optional<String> markerLegacyFingerprint(Path marker) throws IOException {
        return markerValue(marker, "legacy-fingerprint=");
    }

    private Optional<String> markerValue(Path marker, String prefix) throws IOException {
        for (String line : Files.readAllLines(marker)) {
            if (line.startsWith(prefix)) {
                return Optional.of(line.substring(prefix.length()).trim());
            }
        }
        return Optional.empty();
    }

    /**
     * Swaps a staged restore into place, keeping the outgoing database as a
     * timestamped backup. If anything goes wrong the original file is left
     * exactly where it was.
     *
     * <p>Package-private rather than private so the test suite can drive this
     * directly: it is the one method that overwrites a live database.
     */
    void applyPendingRestore(Path targetDir, Path target) throws IOException {
        Path staged = targetDir.resolve(PENDING_RESTORE_FILE);
        Path pendingMarker = targetDir.resolve(PENDING_RESTORE_MARKER);
        if (!Files.exists(staged) || !Files.exists(pendingMarker)) {
            return;
        }

        log.warn("Applying a database restore staged by BackupRestoreService.");

        // Fold the write-ahead log into the main file first: a copy of the main
        // file alone would miss the sales that are only in the -wal.
        boolean foldedIn = false;
        if (Files.exists(target)) {
            foldedIn = checkpointWal(target);
        }

        Path safety = safetyCopyPath(targetDir);
        if (Files.exists(target)) {
            Files.copy(target, safety);
            // Verify before anything else is touched. A safety copy that cannot be
            // opened is not a safety copy, and overwriting a database nobody can
            // fall back to is how a shop loses everything twice.
            try {
                verify(null, safety);
            } catch (IOException ex) {
                throw new IOException("LumiPOS will not restore over this database: the safety copy of "
                        + "it could not be read (" + ex.getMessage() + "). Nothing was replaced; the "
                        + "database still in use is " + target + " and its unreadable copy is " + safety
                        + ". The staged restore is still waiting at " + staged
                        + ". Clear up those files, or remove " + pendingMarker.getFileName()
                        + " to start without restoring, and try again.", ex);
            }
            if (!foldedIn) {
                carryWalAside(targetDir, target, safety);
            }
            pruneSafetyCopies(targetDir, safety, restoreBackupsToKeep(target));
        }

        Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);

        // Never let a -wal from either machine replay onto the new file.
        Files.deleteIfExists(targetDir.resolve(DB_FILE_NAME + "-wal"));
        Files.deleteIfExists(targetDir.resolve(DB_FILE_NAME + "-shm"));

        // Verify while the staged file is still available as the reference, so
        // a truncated copy is caught before the source of truth is deleted.
        verify(staged, target);

        Files.deleteIfExists(staged);
        Files.deleteIfExists(pendingMarker);

        log.warn("Database restore applied. Restart-safe copy of the previous database: " + safety);
    }

    /**
     * Moves the uncheckpointed log out of the live database's way, named after the
     * safety copy it belongs to. It is kept rather than deleted because it holds
     * committed work, but kept where SQLite cannot mistake it for a sidecar of the
     * copy and refuse to open that.
     */
    private void carryWalAside(Path targetDir, Path target, Path safety) {
        String stamp = safety.getFileName().toString().substring(DB_FILE_NAME.length());
        for (String suffix : new String[]{"-wal", "-shm"}) {
            Path sidecar = targetDir.resolve(DB_FILE_NAME + suffix);
            if (!Files.exists(sidecar)) {
                continue;
            }
            Path kept = targetDir.resolve(DB_FILE_NAME + suffix + stamp);
            try {
                Files.move(sidecar, kept, StandardCopyOption.REPLACE_EXISTING);
                log.warn("The live " + sidecar.getFileName() + " could not be folded into the database "
                        + "and was kept as " + kept.getFileName() + " so that nothing written before the "
                        + "crash is thrown away.");
            } catch (IOException ex) {
                log.warn("The live " + sidecar.getFileName() + " could not be kept alongside the safety "
                        + "copy " + safety.getFileName() + ": " + ex.getMessage());
            }
        }
    }

    /**
     * Checkpoints the live database so its main file holds everything committed.
     *
     * <p>A plain JDBC connection is enough and is the only safe one here: this runs
     * before the Spring DataSource exists, so nothing else has the file open, and a
     * checkpoint through the pool would need the pool. Package-private so the test
     * suite can prove the fold-in on its own.
     *
     * @return true when the main file is complete on its own afterwards
     */
    boolean checkpointWal(Path target) {
        if (!Files.exists(targetDirOf(target).resolve(DB_FILE_NAME + "-wal"))) {
            return true;
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            // Columns are busy, log size, checkpointed size; busy 0 means it worked.
            return !rs.next() || rs.getInt(1) == 0;
        } catch (SQLException ex) {
            log.warn("Could not checkpoint the live database before restoring over it: "
                    + ex.getMessage());
            return false;
        }
    }

    /**
     * The stamped name of the safety copy. Two restores inside the same second are
     * possible - a shop working through a bad backup and trying the next one - so a
     * colliding name is numbered rather than reused; replacing a safety copy is the
     * exact loss this whole path is trying to prevent.
     */
    private Path safetyCopyPath(Path targetDir) {
        String stamp = LocalDateTime.now().format(SAFETY_STAMP);
        Path candidate = targetDir.resolve(DB_FILE_NAME + ".pre-restore-" + stamp);
        int collision = 2;
        while (Files.exists(candidate)) {
            candidate = targetDir.resolve(DB_FILE_NAME + ".pre-restore-" + stamp + "-" + collision++);
        }
        return candidate;
    }

    /**
     * Keeps the newest {@code sqlite.restoreBackup.keep} safety copies and deletes
     * the rest, never the one just taken. The setting is read straight from the live
     * database with plain JDBC because this runs before the DataSource exists, and
     * the shop's own number has to be honoured before anything is deleted.
     */
    private void pruneSafetyCopies(Path targetDir, Path justMade, int keep) {
        List<Path> older;
        try (var files = Files.list(targetDir)) {
            older = files
                    .map(path -> (Path) path)
                    .filter(path -> !path.equals(justMade))
                    .filter(path -> path.getFileName().toString().startsWith(DB_FILE_NAME + ".pre-restore-"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.endsWith("-wal") && !name.endsWith("-shm");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            log.warn("The old pre-restore copies could not be listed, so none were deleted: "
                    + ex.getMessage());
            return;
        }

        // The copy just made is one of the kept ones.
        int excess = older.size() - Math.max(0, keep - 1);
        for (int i = 0; i < excess; i++) {
            Path stale = older.get(i);
            String infix = stale.getFileName().toString().substring(DB_FILE_NAME.length());
            for (String suffix : new String[]{"", "-wal", "-shm"}) {
                Path sidecar = targetDir.resolve(DB_FILE_NAME + suffix + infix);
                try {
                    if (Files.deleteIfExists(sidecar)) {
                        log.info("Removed old pre-restore file: " + sidecar.getFileName());
                    }
                } catch (IOException ex) {
                    log.warn("Could not remove " + sidecar.getFileName() + ": " + ex.getMessage());
                }
            }
        }
    }

    /** How many pre-restore copies this shop asked to keep; three when it never said. */
    private int restoreBackupsToKeep(Path target) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT setting_value FROM system_settings "
                     + "WHERE setting_key = '" + RESTORE_KEEP_SETTING + "'")) {
            if (rs.next()) {
                int kept = Integer.parseInt(String.valueOf(rs.getString(1)).trim());
                if (kept >= 1) {
                    return kept;
                }
                log.warn(RESTORE_KEEP_SETTING + " is '" + rs.getString(1)
                        + "'; at least one safety copy has to be kept, using "
                        + DEFAULT_RESTORE_BACKUPS_KEPT + ".");
            }
        } catch (Exception ex) {
            // A database with no settings table yet is a first install, not a problem.
            log.debug("No " + RESTORE_KEEP_SETTING + " setting readable: " + ex.getMessage());
        }
        return DEFAULT_RESTORE_BACKUPS_KEPT;
    }

    private static Path targetDirOf(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        return parent != null ? parent : Paths.get(".");
    }

    /**
     * {@code LUMIPOS_DATA_DIR} / {@code lumipos.data-dir} win when set, so a shop
     * can keep the database on a separate drive. Otherwise Windows uses
     * %LOCALAPPDATA%; other platforms (developer machines) use ~/.lumipos.
     */
    private Path resolveDataDir(ConfigurableEnvironment environment) {
        String override = environment.getProperty("lumipos.data-dir");
        if (override == null || override.isBlank()) {
            override = System.getenv("LUMIPOS_DATA_DIR");
        }
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath();
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Paths.get(localAppData, "LumiPOS", "data").toAbsolutePath();
        }
        return Paths.get(System.getProperty("user.home"), ".lumipos", "data").toAbsolutePath();
    }

    private void migrate(Path legacy, Path target, Path targetDir) throws IOException {
        log.info("Migrating LumiPOS database: " + legacy + " -> " + target);

        // Main file plus WAL sidecars. Missing sidecars are not an error.
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Path source = legacy.resolveSibling(DB_FILE_NAME + suffix);
            if (!Files.exists(source)) {
                continue;
            }
            Files.copy(source, target.resolveSibling(DB_FILE_NAME + suffix),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        verify(legacy, target);

        // The legacy file's identity is recorded so a later start can tell
        // whether it kept being written to after the copy was taken.
        String marker = "migrated-from=" + legacy.toAbsolutePath() + System.lineSeparator()
                + "legacy-fingerprint=" + fingerprint(legacy) + System.lineSeparator();
        Files.write(targetDir.resolve(MARKER_FILE_NAME), marker.getBytes("UTF-8"));

        log.warn("LumiPOS database moved to " + targetDir
                + ". The original file at " + legacy + " was kept as a safety copy "
                + "and can be deleted once the shop confirms the app is working.");
    }

    /**
     * Refuses to continue on a truncated or unreadable copy. Size equality is the
     * cheap check; {@code PRAGMA quick_check} confirms the file is a real
     * database and not a half-written one.
     */
    private void verify(Path legacy, Path target) throws IOException {
        if (legacy != null && Files.exists(legacy)) {
            long expected = Files.size(legacy);
            long actual = Files.size(target);
            if (expected != actual) {
                throw new IOException("Copied database is " + (expected - actual)
                        + " bytes short (expected " + expected + ", got " + actual + ")");
            }
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + target.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA quick_check")) {
            if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                throw new IOException("Copied database failed PRAGMA quick_check");
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            // The copy passed the size check but could not be opened. Treat the
            // file as unverified rather than starting on a suspect database.
            throw new IOException("Copied database could not be opened: " + ex.getMessage(), ex);
        }
    }

    /**
     * addFirst so the resolved path wins over the value in
     * application-sqlite.yml, which still points at the legacy location for
     * installs that were configured before this change.
     */
    private void publishDatasourceUrl(ConfigurableEnvironment environment, Path target) {
        String url = "jdbc:sqlite:" + target.toAbsolutePath()
                + "?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL";
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("spring.datasource.url", url);
        environment.getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, overrides));
    }
}
