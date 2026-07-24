package com.bms.controller;

import com.bms.entity.BackupSetting;
import com.bms.repository.BackupSettingRepository;
import com.bms.service.BackupService;
import com.bms.service.GoogleDriveService;
import com.bms.dto.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/backups")
// ⚠️ REMOVED @PreAuthorize("hasRole('ADMIN')") FROM HERE
public class BackupController {

    private final BackupSettingRepository backupSettingRepository;
    private final BackupService backupService;
    private final GoogleDriveService googleDriveService;

    public BackupController(BackupSettingRepository backupSettingRepository,
                            BackupService backupService,
                            GoogleDriveService googleDriveService) {
        this.backupSettingRepository = backupSettingRepository;
        this.backupService = backupService;
        this.googleDriveService = googleDriveService;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')") // 👈 Moved here
    public ResponseEntity<ApiResponse<BackupSetting>> getSettings() {
        BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> backupSettingRepository.save(new BackupSetting()));
        return ResponseEntity.ok(new ApiResponse<>(true, "Settings retrieved", setting));
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')") // 👈 Moved here
    public ResponseEntity<ApiResponse<BackupSetting>> updateSettings(@RequestBody BackupSetting setting) {
        BackupSetting existing = backupSettingRepository.findFirstByOrderByIdAsc().orElse(new BackupSetting());
        existing.setEnabled(setting.isEnabled());
        existing.setFrequency(setting.getFrequency());
        existing.setCustomCronExpression(setting.getCustomCronExpression());

        if (existing.isEnabled() && existing.getNextBackupDate() == null) {
            LocalDateTime now = LocalDateTime.now();
            switch (existing.getFrequency().toUpperCase()) {
                case "DAILY": existing.setNextBackupDate(now.plusDays(1)); break;
                case "WEEKLY": existing.setNextBackupDate(now.plusWeeks(1)); break;
                case "MONTHLY": existing.setNextBackupDate(now.plusMonths(1)); break;
                case "YEARLY": existing.setNextBackupDate(now.plusYears(1)); break;
                case "CUSTOM": existing.setNextBackupDate(now.plusDays(1)); break;
            }
        } else if (!existing.isEnabled()) {
            existing.setNextBackupDate(null);
        }

        return ResponseEntity.ok(new ApiResponse<>(true, "Settings updated", backupSettingRepository.save(existing)));
    }

    @GetMapping("/google/connect")
    @PreAuthorize("hasRole('ADMIN')") // 👈 Moved here (Frontend calls this with JWT)
    public ResponseEntity<String> connectGoogleDrive() throws Exception {
        return ResponseEntity.ok(googleDriveService.getAuthorizationUrl());
    }

    @GetMapping("/google/callback")
    // ⚠️ NO @PreAuthorize HERE! This allows the browser redirect to pass through.
    public ResponseEntity<Void> handleGoogleCallback(@RequestParam("code") String code) {
        try {
            googleDriveService.handleCallback(code);
            // Redirect back to your frontend settings page
            return ResponseEntity.status(302).header("Location", "http://localhost:5173/settings/backup?status=success").build();
        } catch (Exception e) {
            return ResponseEntity.status(302).header("Location", "http://localhost:5173/settings/backup?status=error").build();
        }
    }

    @PostMapping("/google/disconnect")
    @PreAuthorize("hasRole('ADMIN')") // 👈 Moved here
    public ResponseEntity<ApiResponse<String>> disconnectGoogleDrive() {
        BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc().orElse(null);
        if (setting != null) {
            setting.setGoogleAccessToken(null);
            setting.setGoogleRefreshToken(null);
            setting.setEnabled(false);
            backupSettingRepository.save(setting);
        }
        return ResponseEntity.ok(new ApiResponse<>(true, "Google Drive disconnected", null));
    }

    @PostMapping("/run-now")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> runBackupNow(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            String link = backupService.executeGoogleDriveBackup(startDate, endDate);
            String msg = (startDate != null && endDate != null)
                    ? "Filtered backup completed successfully!"
                    : "Full backup completed successfully!";
            return ResponseEntity.ok(new ApiResponse<>(true, msg, link));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ApiResponse<>(false, "Backup failed: " + e.getMessage(), null));
        }
    }
}