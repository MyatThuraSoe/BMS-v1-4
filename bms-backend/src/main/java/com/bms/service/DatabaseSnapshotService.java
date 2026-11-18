package com.bms.service;

import com.bms.exception.StateConflictException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Takes a bit-for-bit copy of the live database with {@code VACUUM INTO}.
 *
 * <p>Two things need exactly one implementation of this: the manual "upload a full
 * database snapshot" button, and every automatic backup on a shop that runs on
 * SQLite. A snapshot is the only backup that carries the accounting ledgers, the
 * closed periods and the invoice counters, so the scheduler must not be holding a
 * different recipe from the button.
 */
@Service
public class DatabaseSnapshotService {

    /** Names sort the way they were taken, and two backups a day apart stay distinct. */
    private static final DateTimeFormatter NAME_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private final DataSource dataSource;
    private final ShopTimeService shopTimeService;

    public DatabaseSnapshotService(DataSource dataSource, ShopTimeService shopTimeService) {
        this.dataSource = dataSource;
        this.shopTimeService = shopTimeService;
    }

    /**
     * Whether a raw database snapshot is possible here at all. MySQL and H2 have no
     * {@code VACUUM INTO}, so a shop on one of those engines is limited to JSON.
     */
    public boolean isSupported() {
        try (Connection connection = dataSource.getConnection()) {
            return isSqlite(connection);
        } catch (SQLException ex) {
            return false;
        }
    }

    /**
     * {@code LumiPOS_DB_2026-11-17_020000.db}, or {@code ..._manual.db} for a copy a
     * person asked for — the name the restore list shows and retention reads.
     *
     * <p>The marker sits before the extension because that is where the kind of the
     * file is written too: a sealed manual copy is
     * {@code ..._manual.db.enc} and still reads as a database.
     */
    public String snapshotFileName(LocalDateTime takenAt, boolean manual) {
        return GoogleDriveService.SNAPSHOT_PREFIX + takenAt.format(NAME_STAMP)
                + (manual ? GoogleDriveService.MANUAL_MARKER : "") + ".db";
    }

    /**
     * Copies the live database into a temp file that is consistent even while the
     * shop is selling, because {@code VACUUM INTO} reads through SQLite's own
     * snapshot rather than copying a WAL file by hand.
     *
     * @return the temp file; the caller owns it and must delete it when done
     */
    public Path createSnapshot() throws SQLException, IOException {
        Path target = Files.createTempFile("lumipos_snapshot_", ".db");
        Files.deleteIfExists(target);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            if (!isSqlite(connection)) {
                throw new StateConflictException(
                        "Raw database snapshots are only available with the SQLite engine. "
                                + "Use a JSON backup instead for MySQL installs.");
            }
            String path = target.toAbsolutePath().toString().replace("'", "''");
            statement.execute("VACUUM INTO '" + path + "'");
        } catch (SQLException | RuntimeException ex) {
            // Nothing useful is left behind when the snapshot failed, and a
            // half-written .db invites someone to try restoring from it.
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // Best effort.
            }
            throw ex;
        }
        return target;
    }

    private static boolean isSqlite(Connection connection) throws SQLException {
        String url = connection.getMetaData().getURL();
        return url != null && url.contains(":sqlite:");
    }

    /**
     * Snapshot, verified upload and cleanup as one unit — the thing both the
     * scheduler and the manual button need. The temp file never outlives the
     * attempt, whether the upload succeeded, was rejected by Drive, or threw.
     *
     * <p>The sealing happens here rather than in the caller because Drive verifies
     * the bytes it was given: the file that is checked must be the file that was
     * sent, so the encrypted copy has to exist before the upload starts.
     *
     * @param passphrase encrypts the snapshot when non-null; null uploads it plain
     * @param manual     names the copy so retention leaves it to the shop to delete
     */
    public GoogleDriveService.DriveUpload uploadSnapshotToDrive(GoogleDriveService drive, String folderId,
                                                               LocalDateTime takenAt,
                                                               char[] passphrase,
                                                               boolean manual) throws Exception {
        Path snapshot = createSnapshot();
        try {
            String fileName = snapshotFileName(takenAt, manual);
            if (passphrase == null) {
                return drive.uploadVerified(snapshot.toFile(), "application/x-sqlite3", folderId, fileName);
            }
            Path sealed = BackupEncryption.encrypt(snapshot, passphrase);
            try {
                return drive.uploadVerified(sealed.toFile(), "application/octet-stream", folderId,
                        fileName + BackupEncryption.ENCRYPTED_SUFFIX);
            } finally {
                Files.deleteIfExists(sealed);
            }
        } finally {
            try {
                Files.deleteIfExists(snapshot);
            } catch (IOException ignored) {
                // A leftover temp file is harmless.
            }
        }
    }
}
