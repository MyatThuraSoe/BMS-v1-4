package com.bms.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
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

    // IDLE / SUCCESS / FAILED — last outcome of a backup attempt
    private String backupStatus = "IDLE";

    @Column(length = 2000)
    private String lastErrorMessage;

    private LocalDateTime lastBackupAttempt;

    @Column(length = 1000)
    private String googleRefreshToken;

    @Column(length = 1000)
    private String googleAccessToken;

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
}