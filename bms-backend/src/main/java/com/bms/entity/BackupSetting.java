package com.bms.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import com.fasterxml.jackson.annotation.JsonProperty;

@Entity
@Table(name = "backup_settings")
public class BackupSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @JsonProperty("isEnabled")
    private boolean isEnabled = false;

    @Column(nullable = false)
    private String frequency = "WEEKLY"; // DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM

    private String customCronExpression; // Used if frequency is CUSTOM

    private LocalDateTime lastBackupDate;

    private LocalDateTime nextBackupDate;

    /**
     * The time of day the automatic backup runs at, in the shop's own clock. Null means the
     * moment automation was switched on, which is what the schedule is anchored to until the
     * shop picks a time.
     */
    private LocalTime scheduledTime;

    // IDLE / SUCCESS / PARTIAL / FAILED — last outcome of a backup attempt
    private String backupStatus = "IDLE";

    @Column(length = 2000)
    private String lastErrorMessage;

    private LocalDateTime lastBackupAttempt;

    @Column(length = 1000)
    private String googleRefreshToken;

    @Column(length = 1000)
    private String googleAccessToken;

    /**
     * How many backups of each kind are kept in Google Drive before the oldest are
     * deleted. Null means the shop never chose, which the backup pass reads as
     * {@code BackupService.DEFAULT_RETENTION_COUNT}; a nullable column rather than
     * a NOT NULL one so a database that predates the feature accepts the upgrade.
     */
    @Column(name = "retention_count")
    private Integer retentionCount;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public String getCustomCronExpression() {
        return customCronExpression;
    }

    public void setCustomCronExpression(String customCronExpression) {
        this.customCronExpression = customCronExpression;
    }

    public LocalDateTime getLastBackupDate() {
        return lastBackupDate;
    }

    public void setLastBackupDate(LocalDateTime lastBackupDate) {
        this.lastBackupDate = lastBackupDate;
    }

    public LocalDateTime getNextBackupDate() {
        return nextBackupDate;
    }

    public void setNextBackupDate(LocalDateTime nextBackupDate) {
        this.nextBackupDate = nextBackupDate;
    }

    public LocalTime getScheduledTime() {
        return scheduledTime;
    }

    public void setScheduledTime(LocalTime scheduledTime) {
        this.scheduledTime = scheduledTime;
    }

    public String getBackupStatus() {
        return backupStatus;
    }

    public void setBackupStatus(String backupStatus) {
        this.backupStatus = backupStatus;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    public LocalDateTime getLastBackupAttempt() {
        return lastBackupAttempt;
    }

    public void setLastBackupAttempt(LocalDateTime lastBackupAttempt) {
        this.lastBackupAttempt = lastBackupAttempt;
    }

    public String getGoogleRefreshToken() {
        return googleRefreshToken;
    }

    public void setGoogleRefreshToken(String googleRefreshToken) {
        this.googleRefreshToken = googleRefreshToken;
    }

    public String getGoogleAccessToken() {
        return googleAccessToken;
    }

    public void setGoogleAccessToken(String googleAccessToken) {
        this.googleAccessToken = googleAccessToken;
    }

    public Integer getRetentionCount() {
        return retentionCount;
    }

    public void setRetentionCount(Integer retentionCount) {
        this.retentionCount = retentionCount;
    }

    /**
     * What the automatic backup actually writes: DB, JSON or BOTH. Not a column —
     * the switch is the {@code backup.format} system setting, and what the shop
     * gets when nobody set one depends on the database engine, which only the
     * server knows.
     */
    @Transient
    private String backupFormat;

    /** Whether a whole-database snapshot is possible here at all. */
    @Transient
    private Boolean snapshotSupported;

    public String getBackupFormat() {
        return backupFormat;
    }

    public void setBackupFormat(String backupFormat) {
        this.backupFormat = backupFormat;
    }

    public Boolean getSnapshotSupported() {
        return snapshotSupported;
    }

    public void setSnapshotSupported(Boolean snapshotSupported) {
        this.snapshotSupported = snapshotSupported;
    }

    /**
     * Whether manual backups get encrypted before upload. Not a column: the switch
     * is the {@code backup.encryption.enabled} system setting, and the passphrase
     * it goes with is never stored anywhere — a shop that forgets it has no way
     * round that, and a database full of secrets would be one.
     */
    @Transient
    private Boolean encryptionEnabled;

    public Boolean getEncryptionEnabled() {
        return encryptionEnabled;
    }

    public void setEncryptionEnabled(Boolean encryptionEnabled) {
        this.encryptionEnabled = encryptionEnabled;
    }
}