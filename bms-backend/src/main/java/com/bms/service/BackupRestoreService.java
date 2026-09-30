package com.bms.service;

import com.bms.service.GoogleDriveService.DriveBackupFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final Logger log = LoggerFactory.getLogger(BackupRestoreService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Staged raw database, applied by SqliteDataDirMigration on the next start. */
    public static final String PENDING_RESTORE_FILE = "lumipos-restored.db";
    public static final String PENDING_RESTORE_MARKER = ".lumipos-pending-restore";

    private final GoogleDriveService googleDriveService;
    private final DataImportService dataImportService;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.upload-dir:C:/LumiPOS/uploads}")
    private String uploadDir;

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

        executor.submit(() -> runJsonRestore(jobId, fileId, sizeBytes, mode));
        return jobId;
    }

    public String startDatabaseSnapshot() {
        String jobId = newJobId();
        RestoreJob job = new RestoreJob(jobId, "db-snapshot", "RUNNING", 0,
                "Preparing database snapshot...", null, Instant.now().toString(), null);
        jobs.put(jobId, job);

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
            String name = "LumiPOS_DB_" + java.time.LocalDateTime.now()
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
     */
    public String startDatabaseRestore(String fileId, long sizeBytes) {
        String jobId = newJobId();
        jobs.put(jobId, new RestoreJob(jobId, "db-restore", "RUNNING", 0,
                "Starting database restore...", null, Instant.now().toString(), null));

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

                update(jobId, 90, "Checking the downloaded database...");
                assertUsableSqlite(staged);

                update(jobId, 96, "Preparing the database for the next start...");
                Path target = resolvePendingRestore();
                Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
                // Sidecars must not survive: a -wal from the old machine would be
                // replayed onto the new file and corrupt it.
                Files.deleteIfExists(target.resolveSibling("lumipos.db-wal"));
                Files.deleteIfExists(target.resolveSibling("lumipos.db-shm"));
                Files.writeString(resolvePendingMarker(),
                        "restored-from-drive=" + Instant.now() + System.lineSeparator());

                finish(jobId, "SUCCESS", 100,
                        "Database downloaded and staged. Restart LumiPOS to finish the restore.", null);
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

    /** Rejects a file that is not a working SQLite database before it is staged. */
    private void assertUsableSqlite(Path file) throws IOException {
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement s = c.createStatement();
             java.sql.ResultSet rs = s.executeQuery("PRAGMA quick_check")) {
            if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                throw new IOException("That backup is not a valid LumiPOS database.");
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("That file could not be opened as a LumiPOS database: " + ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------- job state

    private String newJobId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
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
