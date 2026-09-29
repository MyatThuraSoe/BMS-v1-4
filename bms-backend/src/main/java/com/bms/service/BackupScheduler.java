package com.bms.service;

import com.bms.entity.BackupSetting;
import com.bms.repository.BackupSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Runs automated Google Drive backups when the configured time arrives.
 * Polls every 10 minutes (cheap: one row read) and fires exactly when
 * nextBackupDate is reached. CUSTOM cron windows are honored through
 * BackupService.calculateNextBackupDate.
 */
@Component
public class BackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

    private final BackupService backupService;
    private final BackupSettingRepository backupSettingRepository;

    public BackupScheduler(BackupService backupService,
                           BackupSettingRepository backupSettingRepository) {
        this.backupService = backupService;
        this.backupSettingRepository = backupSettingRepository;
    }

    @Scheduled(fixedDelay = 600_000)
    public void runDueBackups() {
        try {
            BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc().orElse(null);
            if (setting == null || !setting.isEnabled()) {
                return;
            }
            if (setting.getGoogleRefreshToken() == null || setting.getGoogleRefreshToken().isEmpty()) {
                return;
            }
            LocalDateTime next = setting.getNextBackupDate();
            if (next == null || LocalDateTime.now().isBefore(next)) {
                return;
            }
            log.info("Scheduled backup is due — running Google Drive backup");
            backupService.runBackupNow(null, null);
            log.info("Scheduled backup completed");
        } catch (Exception e) {
            // Failure outcome was already recorded by runBackupNow; don't spam logs.
            log.warn("Scheduled backup attempt failed: {}", e.getMessage());
        }
    }
}