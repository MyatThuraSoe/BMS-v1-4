package com.bms.service;

import com.bms.service.GoogleDriveService.DriveBackupFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Restore path for a shop that lost its database, plus raw database snapshots
 * for total PC loss.
 *
 * <p>Two kinds of backup answer two different disasters:
 * <ul>
 *   <li><b>JSON backup</b> — portable and version-tolerant. Restored through the
 *       existing {@link DataImportService} in either REPLACE_ALL or MERGE mode.</li>
 *   <li><b>Raw {@code .db} snapshot</b> — a bit-for-bit copy taken with
 *       {@code VACUUM INTO}, which is consistent even while the shop is selling.
 *       Used when the PC itself is gone.</li>
 * </ul>
 *
 * <p>Restores run as background jobs so the settings page can show a real
 * progress bar instead of a spinner that may sit still for minutes.
 *
 * <p>A raw database cannot be swapped in while the DataSource holds it open, so
 * it is staged next to the live file and applied by
 * {@code SqliteDataDirMigration} on the next start. Swapping a live SQLite file
 * underneath a running JVM is how databases get corrupted.
 */
@Service
public class BackupRestoreService {

    @Autowired
    private ShopTimeService shopTimeService;

    @Autowired
    private AuditLogService auditLogService;

    private static final Logger log = LoggerFactory.getLogger(BackupRestoreService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_RETAINED_JOBS = 20;

    /**
     * What a raw database has to contain before it is allowed to replace a shop's
     * live data. {@code PRAGMA quick_check} alone passes an empty file or a
     * database from another program, and after the next start the shop would see
     * an app with no users, no products and no sales.
     */
    static final List<String> REQUIRED_TABLES =
            List.of("users", "roles", "products", "sales", "system_settings", "shop_info");

    /** A SQLite file under one page is not a database someone actually used. */
    static final long MINIMUM_DATABASE_BYTES = 4096L;

    static final String NOT_LUMIPOS_BACKUP = "That file is a database, but not a LumiPOS backup.";

    /** Staged raw database, applied by SqliteDataDirMigration on the next start. */
    public static final String PENDING_RESTORE_FILE = "lumipos-restored.db";
    public static final String PENDING_RESTORE_MARKER = ".lumipos-pending-restore";

    private final GoogleDriveService googleDriveService;
    private final DataImportService dataImportService;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, RestoreJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "lumipos-restore");
        t.setDaemon(true);
        return t;
    });

    public BackupRestoreService(GoogleDriveService googleDriveService,
                                DataImportService dataImportService,
                                DataSource dataSource) {
        this.googleDriveService = googleDriveService;
        this.dataImportService = dataImportService;
        this.dataSource = dataSource;
    }

    public List<DriveBackupFile> listDriveBackups() throws Exception {
        String folderId = googleDriveService.getOrCreateBackupFolderId(googleDriveService.getDriveService());
        return googleDriveService.listBackupFiles(folderId);
    }

    public RestoreJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Stages a downloaded raw database for the next start. Also used by the
     * "restore from a file on disk" button.
     */
    public boolean isRestorePending() {
        return Files.exists(resolvePendingRestore()) && Files.exists(resolvePendingMarker());
    }

    // ------------------------------------------------------------------ jobs

    public String startJsonRestore(String fileId, long sizeBytes, DataImportService.ImportMode mode) {
        String jobId = newJobId();
        RestoreJob job = new RestoreJob(jobId, "json-restore", "RUNNING", 0,
                "Starting restore...", null, Instant.now().toString(), null);
        jobs.put(jobId, job);
        evictOldJobs();

        executor.submit(() -> runJsonRestore(jobId, fileId, sizeBytes, mode));
        return jobId;
    }

    public String startDatabaseSnapshot() {
        String jobId = newJobId();
        RestoreJob job = new RestoreJob(jobId, "db-snapshot", "RUNNING", 0,
                "Preparing database snapshot...", null, Instant.now().toString(), null);
        jobs.put(jobId, job);
        evictOldJobs();

        executor.submit(() -> runDatabaseSnapshot(jobId));
        return jobId;
    }

    private void runJsonRestore(String jobId, String fileId, long sizeBytes,
                                DataImportService.ImportMode mode) {
        Path temp = null;
        try {
            // Phase 1: download (0-40%). Byte-accurate, and for a Drive restore
            // this is the only phase the user actually waits on.
            update(jobId, 2, "Downloading backup from Google Drive...");
            temp = Files.createTempFile("lumipos_restore_", ".json");
            long[] progress = {0L};
            final String jid = jobId;
            final long total = sizeBytes;
            googleDriveService.downloadFile(fileId, temp.toFile(), written -> {
                int pct = total > 0 ? (int) Math.min(38, (written * 38) / total) : 20;
                // Throttle: a 64 KB chunk would otherwise push a React render
                // per chunk.
                if (pct - progress[0] >= 2 || pct >= 38) {
                    progress[0] = pct;
                    update(jid, 2 + pct, "Downloading backup from Google Drive...");
                }
            });

            // Phase 2: validate (40-50%).
            update(jobId, 42, "Checking the backup file...");
            String json = Files.readString(temp, StandardCharsets.UTF_8);
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            if (parsed.get("data") == null) {
                throw new IllegalArgumentException(
                        "That file is not a LumiPOS backup (no 'data' section).");
            }
            update(jobId, 50, "Backup looks valid. Restoring your data...");

            // Phase 3: import (50-95%). Reported as a phase, not a byte count:
            // importAll is one transaction and gives no per-table signal, and
            // instrumenting that method would put the shop's data at risk for a
            // cosmetic progress bar.
            Map<String, Object> summary = dataImportService.importAll(parsed, mode);
            update(jobId, 97, "Finishing up...");

            finish(jobId, "SUCCESS", 100, describeSummary(summary, mode), null);
        } catch (Exception ex) {
            log.error("JSON restore job {} failed", jobId, ex);
            fail(jobId, friendlyMessage(ex));
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // A leftover temp file is harmless.
                }
            }
        }
    }

    private void runDatabaseSnapshot(String jobId) {
        Path snapshot = null;
        try {
            // VACUUM INTO writes a consistent snapshot even with concurrent
            // writers, which a plain file copy of a WAL database does not.
            update(jobId, 10, "Creating a consistent database snapshot...");
            snapshot = createConsistentSnapshot();

            update(jobId, 60, "Uploading snapshot to Google Drive...");
            String folderId = googleDriveService.getOrCreateBackupFolderId(googleDriveService.getDriveService());
            String name = "LumiPOS_DB_" + shopTimeService.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")) + ".db";
            googleDriveService.uploadFile(snapshot.toFile(), "application/x-sqlite3", folderId, name);

            finish(jobId, "SUCCESS", 100, "Database snapshot uploaded as " + name, null);
        } catch (Exception ex) {
            log.error("Database snapshot job {} failed", jobId, ex);
            fail(jobId, friendlyMessage(ex));
        } finally {
            if (snapshot != null) {
                try {
                    Files.deleteIfExists(snapshot);
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
        }
    }

    /**
     * Takes a consistent copy of the live SQLite database using
     * {@code VACUUM INTO}, which is safe while other connections are writing.
     * Returns null for a non-SQLite engine, where a raw snapshot makes no sense.
     */
    public Path createConsistentSnapshot() throws SQLException, IOException {
        Path target = Files.createTempFile("lumipos_snapshot_", ".db");
        Files.deleteIfExists(target);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            String url = connection.getMetaData().getURL();
            if (url == null || !url.contains(":sqlite:")) {
                throw new IllegalStateException(
                        "Raw database snapshots are only available with the SQLite engine. "
                                + "Use a JSON backup instead for MySQL installs.");
            }
            statement.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
        }
        return target;
    }

    /**
     * Downloads a raw database from Drive and stages it for the next start.
     * The caller must restart the app (or the shop can do it later).
     *
     * @param actorUserId the admin who pressed the button, for the audit trail
     */
    public String startDatabaseRestore(String fileId, long sizeBytes, Long actorUserId) {
        String jobId = newJobId();
        jobs.put(jobId, new RestoreJob(jobId, "db-restore", "RUNNING", 0,
                "Starting database restore...", null, Instant.now().toString(), null));
        evictOldJobs();

        executor.submit(() -> {
            Path staged = null;
            try {
                update(jobId, 2, "Downloading database from Google Drive...");
                staged = Files.createTempFile("lumipos_dbrestore_", ".db");
                final String jid = jobId;
                final long total = sizeBytes;
                final int[] last = {0};
                googleDriveService.downloadFile(fileId, staged.toFile(), written -> {
                    int pct = total > 0 ? (int) Math.min(85, (written * 85) / total) : 40;
                    if (pct - last[0] >= 3 || pct >= 85) {
                        last[0] = pct;
                        update(jid, 2 + pct, "Downloading database from Google Drive...");
                    }
                });

                update(jobId, 88, "Checking the downloaded database...");
                StagedDatabase result = stageDatabase(
                        staged, Paths.get(resolveDataDir()), fileId, sizeBytes, actorUserId);
                update(jobId, 96, result.checkStep());

                finish(jobId, "SUCCESS", 100, "Database downloaded and staged. "
                        + "Restart LumiPOS to finish the restore."
                        + (result.schemaWarning() == null ? "" : " " + result.schemaWarning()), null);
            } catch (Exception ex) {
                log.error("Database restore job {} failed", jobId, ex);
                fail(jobId, friendlyMessage(ex));
            } finally {
                if (staged != null) {
                    try {
                        Files.deleteIfExists(staged);
                    } catch (IOException ignored) {
                        // Best effort.
                    }
                }
            }
        });
        return jobId;
    }

    /**
     * Proves a downloaded file really is a LumiPOS backup and parks it next to
     * the live database for {@code SqliteDataDirMigration} to swap in. Nothing in
     * the data directory is touched until every check has passed, so a refused
     * file can never take effect on the next start.
     *
     * @param dataDir where the staged file goes; passed in so a check can be run
     *                without pointing at the shop's real folder
     * @return what the shop should be told about the file
     */
    StagedDatabase stageDatabase(Path downloaded, Path dataDir, String fileId, long sizeBytes,
                                 Long actorUserId) throws IOException {
        assertUsableSqlite(downloaded);

        String warning = null;
        List<String> unknown;
        try {
            unknown = unknownTables(downloaded, liveTables());
        } catch (Exception ex) {
            // Not being able to compare schemas is a missing answer, not a failed
            // check: the file has already been proved to be a LumiPOS backup.
            log.warn("The backup's schema could not be compared with this installation", ex);
            unknown = List.of();
        }
        if (!unknown.isEmpty()) {
            log.warn("Backup {} carries tables this app does not know: {}", fileId, unknown);
            warning = "This backup was made by a newer LumiPOS. It carries tables this version does "
                    + "not know (" + String.join(", ", unknown) + "), which will be left alone.";
        }

        Path target = dataDir.resolve(PENDING_RESTORE_FILE);
        Files.copy(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
        // Only the main file is staged. The live database's own -wal is left where
        // it is, because the DataSource has that file open and deleting it from
        // underneath a running shop is how recent sales disappear. It is dealt with
        // at the next start, by SqliteDataDirMigration, once nothing holds it.
        Files.writeString(dataDir.resolve(PENDING_RESTORE_MARKER),
                "restored-from-drive=" + Instant.now() + System.lineSeparator());

        auditLogService.logAction(actorUserId, "DB_RESTORE_STAGED",
                "Raw database staged for the next start from Drive file " + fileId
                        + " (" + sizeBytes + " bytes)",
                "Database", null, null, null);

        return new StagedDatabase("Checking the database... ok", warning);
    }

    /**
     * Rejects anything that is not a workable LumiPOS database before it is
     * staged. The message deliberately does not name the missing table: the shop
     * cannot act on that, and a precise list invites someone to hand-edit a
     * database to get past the check. The reason goes to the log.
     */
    void assertUsableSqlite(Path file) throws IOException {
        long size = Files.size(file);
        if (size < MINIMUM_DATABASE_BYTES) {
            log.warn("Restore candidate {} rejected: {} bytes is not a database anyone has used.",
                    file.getFileName(), size);
            throw new IOException(NOT_LUMIPOS_BACKUP);
        }

        try (Connection connection = openForInspection(file)) {
            String verdict;
            try (Statement statement = connection.createStatement();
                 java.sql.ResultSet rs = statement.executeQuery("PRAGMA quick_check")) {
                verdict = rs.next() ? rs.getString(1) : null;
            }
            if (!"ok".equalsIgnoreCase(verdict)) {
                // Not a working database at all, so it deserves a different answer
                // from one that is fine but belongs to another program.
                log.warn("Restore candidate {} rejected: quick_check said '{}'.",
                        file.getFileName(), verdict);
                throw new IOException("That file could not be opened as a LumiPOS database: " + verdict);
            }

            Set<String> tables = tableNames(connection);
            for (String required : REQUIRED_TABLES) {
                if (!tables.contains(required)) {
                    log.warn("Restore candidate {} rejected: the table '{}' is missing.",
                            file.getFileName(), required);
                    throw new IOException(NOT_LUMIPOS_BACKUP);
                }
            }

            if (livingUserCount(connection) < 1) {
                log.warn("Restore candidate {} rejected: nobody could sign in afterwards.",
                        file.getFileName());
                throw new IOException(NOT_LUMIPOS_BACKUP);
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("That file could not be opened as a LumiPOS database: "
                    + ex.getMessage(), ex);
        }
    }

    /**
     * Opens a downloaded database without writing to it.
     *
     * <p>The read-only request can legitimately fail: a file flagged as WAL needs
     * its {@code -wal} and {@code -shm} beside it, and a single-file backup downloaded
     * from Drive never brings them. That is not a bad backup, so the open falls back to
     * the ordinary one. The download is a temp copy this process owns and deletes, and
     * everything run against it is a read.
     */
    private Connection openForInspection(Path file) throws SQLException {
        org.sqlite.SQLiteConfig readOnly = new org.sqlite.SQLiteConfig();
        readOnly.setReadOnly(true);
        try {
            return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(),
                    readOnly.toProperties());
        } catch (SQLException ex) {
            log.debug("Could not open {} read-only ({}); inspecting it normally instead.",
                    file.getFileName(), ex.getMessage());
            return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        }
    }

    private Set<String> tableNames(Connection connection) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && !name.toLowerCase(Locale.ROOT).startsWith("sqlite_")) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    /**
     * Rows in {@code users} somebody could actually sign in with. A backup whose
     * accounts are all soft-deleted restores a shop nobody can open.
     */
    private long livingUserCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM users WHERE deleted_at IS NULL")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException ex) {
            // A backup old enough to predate soft deletion still holds real
            // accounts, so counting every row is the honest reading of the check.
            try (Statement statement = connection.createStatement();
                 java.sql.ResultSet rs = statement.executeQuery("SELECT count(*) FROM users")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Tables in the backup this installation has never seen, sorted for the message. */
    List<String> unknownTables(Path file, Set<String> appTables) throws IOException {
        Set<String> known = appTables.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        try (Connection connection = openForInspection(file)) {
            List<String> extra = new ArrayList<>(tableNames(connection));
            extra.removeAll(known);
            Collections.sort(extra);
            return extra;
        } catch (SQLException ex) {
            throw new IOException("The backup's table list could not be read: " + ex.getMessage(), ex);
        }
    }

    /** The tables this app was built with, read from its own live database. */
    private Set<String> liveTables() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             java.sql.ResultSet rs = connection.getMetaData()
                     .getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !name.toLowerCase(Locale.ROOT).startsWith("sqlite_")) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    // ------------------------------------------------------------- job state

    private String newJobId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Caps the in-memory job history. Without this a shop that snapshots daily
     * accumulates a RestoreJob per run for the lifetime of the process; the
     * results are only ever needed while the settings page is open.
     */
    private void evictOldJobs() {
        if (jobs.size() <= MAX_RETAINED_JOBS) {
            return;
        }
        jobs.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().startedAt()))
                .limit(jobs.size() - MAX_RETAINED_JOBS)
                .map(Map.Entry::getKey)
                .forEach(jobs::remove);
    }

    private void update(String jobId, int percent, String step) {
        jobs.computeIfPresent(jobId, (id, job) -> job.withProgress(percent, step));
    }

    private void finish(String jobId, String status, int percent, String step, String error) {
        jobs.computeIfPresent(jobId, (id, job) -> job.completed(status, percent, step, error));
    }

    private void fail(String jobId, String message) {
        jobs.computeIfPresent(jobId, (id, job) -> job.completed("FAILED", job.percent(), "Restore failed", message));
    }

    private String describeSummary(Map<String, Object> summary, DataImportService.ImportMode mode) {
        String label = mode == DataImportService.ImportMode.MERGE
                ? "Data merged into the shop."
                : "Shop data replaced with the backup.";
        return label + " " + (summary == null ? "" : summary.toString());
    }

    private String friendlyMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "The restore could not be completed. Please try again.";
        }
        return message;
    }

    private Path resolvePendingRestore() {
        return Paths.get(resolveDataDir()).resolve(PENDING_RESTORE_FILE);
    }

    private Path resolvePendingMarker() {
        return Paths.get(resolveDataDir()).resolve(PENDING_RESTORE_MARKER);
    }

    /**
     * Same resolution order as SqliteDataDirMigration, kept in one place so the
     * staged file always lands where the swap expects it.
     */
    public String resolveDataDir() {
        String override = System.getenv("LUMIPOS_DATA_DIR");
        if (override != null && !override.isBlank()) {
            return override;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Paths.get(localAppData, "LumiPOS", "data").toAbsolutePath().toString();
        }
        return Paths.get(System.getProperty("user.home"), ".lumipos", "data").toAbsolutePath().toString();
    }

    /**
     * What a staged database is worth telling the shop about.
     *
     * @param checkStep     the line the job shows once the file passed its checks
     * @param schemaWarning null when the backup matches this app, otherwise the
     *                      note about a backup from a newer version
     */
    public record StagedDatabase(String checkStep, String schemaWarning) {
    }

    /**
     * Progress of one long-running restore/backup job.
     */
    public record RestoreJob(String id, String kind, String status, int percent, String step,
                             String error, String startedAt, String finishedAt) {
        RestoreJob withProgress(int newPercent, String newStep) {
            return new RestoreJob(id, kind, status, Math.max(0, Math.min(100, newPercent)),
                    newStep, error, startedAt, finishedAt);
        }

        RestoreJob completed(String newStatus, int newPercent, String newStep, String newError) {
            return new RestoreJob(id, kind, newStatus, Math.max(0, Math.min(100, newPercent)),
                    newStep, newError, startedAt, Instant.now().toString());
        }
    }
}
