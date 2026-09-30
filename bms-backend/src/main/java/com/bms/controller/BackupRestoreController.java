package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.service.BackupRestoreService;
import com.bms.service.DataImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Restore endpoints: bring a shop back from Google Drive after the database is
 * lost, and take raw database snapshots for total PC loss.
 *
 * <p>ADMIN-only: restoring rewrites the shop's entire data set, so it is never
 * exposed to a cashier account.
 */
@RestController
@RequestMapping("/api/backups")
@PreAuthorize("hasRole('ADMIN')")
public class BackupRestoreController {

    private final BackupRestoreService backupRestoreService;

    public BackupRestoreController(BackupRestoreService backupRestoreService) {
        this.backupRestoreService = backupRestoreService;
    }

    /** Backups available in the connected Google Drive folder, newest first. */
    @GetMapping("/drive/files")
    public ResponseEntity<ApiResponse<Object>> listDriveFiles() {
        try {
            return ResponseEntity.ok(new ApiResponse<>(true, "Drive backups retrieved",
                    backupRestoreService.listDriveBackups()));
        } catch (Exception ex) {
            return ResponseEntity.status(502).body(new ApiResponse<>(false,
                    "Could not reach Google Drive: " + ex.getMessage(), null));
        }
    }

    /** Restore a JSON backup, in either replace-everything or merge mode. */
    @PostMapping("/drive/restore")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restoreFromDrive(
            @RequestBody DriveRestoreRequest request) {

        DataImportService.ImportMode mode;
        try {
            mode = DataImportService.ImportMode.valueOf(request.mode());
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(new ApiResponse<>(false,
                    "Unknown restore mode: " + request.mode(), null));
        }

        String jobId = backupRestoreService.startJsonRestore(
                request.fileId(), request.sizeBytes(), mode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        return ResponseEntity.accepted()
                .body(new ApiResponse<>(true, "Restore started", body));
    }

    /**
     * Download a raw database snapshot and stage it for the next start.
     * The app must be restarted before the new database is used.
     */
    @PostMapping("/drive/restore-database")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restoreDatabaseFromDrive(
            @RequestBody DriveRestoreRequest request) {

        String jobId = backupRestoreService.startDatabaseRestore(
                request.fileId(), request.sizeBytes());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        return ResponseEntity.accepted()
                .body(new ApiResponse<>(true, "Database restore started", body));
    }

    /** Take a consistent snapshot of the live database and upload it to Drive. */
    @PostMapping("/drive/snapshot")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createDatabaseSnapshot() {
        String jobId = backupRestoreService.startDatabaseSnapshot();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        return ResponseEntity.accepted()
                .body(new ApiResponse<>(true, "Database snapshot started", body));
    }

    /** Poll for restore/snapshot progress. */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ApiResponse<BackupRestoreService.RestoreJob>> getJob(
            @PathVariable String jobId) {

        BackupRestoreService.RestoreJob job = backupRestoreService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.status(404)
                    .body(new ApiResponse<>(false, "Unknown job", null));
        }
        return ResponseEntity.ok(new ApiResponse<>(true, "Job status", job));
    }

    /** True when a downloaded database is waiting for a restart to be applied. */
    @GetMapping("/restore-pending")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restorePending() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pending", backupRestoreService.isRestorePending());
        return ResponseEntity.ok(new ApiResponse<>(true, "Pending restore status", body));
    }

    public record DriveRestoreRequest(String fileId, long sizeBytes, String mode) {
    }
}
