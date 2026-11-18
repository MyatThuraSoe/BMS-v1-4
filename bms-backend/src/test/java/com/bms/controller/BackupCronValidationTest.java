package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.entity.BackupSetting;
import com.bms.repository.BackupSettingRepository;
import com.bms.repository.SystemSettingRepository;
import com.bms.service.BackupService;
import com.bms.service.DataExportService;
import com.bms.service.DatabaseSnapshotService;
import com.bms.service.GoogleDriveService;
import com.bms.service.ShopTimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A cron line the server cannot read is not a schedule. The settings page used to
 * accept any text and quietly plan a daily backup, so a shop that typed
 * "0 0 2 * * L" (a Spring-only token that does not exist) kept its 02:00 habit for
 * about a year before noticing it ran every night.
 */
class BackupCronValidationTest {

    private BackupSettingRepository repository;
    private BackupSetting savedRow;
    private ShopTimeService shopTime;
    private BackupController controller;

    @BeforeEach
    void wireTheSettingsPage() {
        shopTime = mock(ShopTimeService.class);
        when(shopTime.now()).thenReturn(LocalDateTime.parse("2026-11-17T09:00:00"));
        BackupService backupService = new BackupService(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                mock(SystemSettingRepository.class), mock(DatabaseSnapshotService.class));
        ReflectionTestUtils.setField(backupService, "shopTimeService", shopTime);

        repository = mock(BackupSettingRepository.class);
        when(repository.findFirstByOrderByIdAsc()).thenAnswer(call -> Optional.of(savedRow));
        when(repository.save(any(BackupSetting.class))).thenAnswer(call -> call.getArgument(0));

        controller = new BackupController(repository, backupService,
                mock(GoogleDriveService.class), mock(DataExportService.class), shopTime);
    }

    /** What the settings page sends for a shop that picked "Custom". */
    private BackupSetting customForm(String cronText) {
        BackupSetting request = new BackupSetting();
        request.setEnabled(true);
        request.setFrequency("CUSTOM");
        request.setCustomCronExpression(cronText);
        return request;
    }

    private BackupSetting storedRow() {
        BackupSetting row = new BackupSetting();
        row.setEnabled(false);
        row.setFrequency("WEEKLY");
        return row;
    }

    private ResponseEntity<ApiResponse<BackupSetting>> save(String cronText) {
        savedRow = storedRow();
        return controller.updateSettings(customForm(cronText));
    }

    @Test
    void textTheServerCannotReadIsRefusedRatherThanQuietlyMadeDaily() {
        ResponseEntity<ApiResponse<BackupSetting>> response = save("0 0 2 * * L");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("That schedule text is not valid", response.getBody().getMessage());
        verify(repository, never()).save(any(BackupSetting.class));
    }

    @Test
    void aScheduleThatNeverFiresAgainIsRefusedToo() {
        ResponseEntity<ApiResponse<BackupSetting>> response = save("0 0 2 30 2 *");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "the 30th of February parses, but a shop that never runs a backup was never told");
        verify(repository, never()).save(any(BackupSetting.class));
    }

    @Test
    void aScheduleTighterThanFifteenMinutesIsRefused() {
        ResponseEntity<ApiResponse<BackupSetting>> response = save("0 * * * * *");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(BackupService.TOO_OFTEN_CRON_TEXT_MESSAGE, response.getBody().getMessage());
        verify(repository, never()).save(any(BackupSetting.class));
    }

    @Test
    void aNightlyScheduleIsSavedAndPlanned() {
        ResponseEntity<ApiResponse<BackupSetting>> response = save("0 0 2 * * *");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        BackupSetting saved = response.getBody().getData();
        assertEquals(LocalDateTime.parse("2026-11-18T02:00:00"), saved.getNextBackupDate());
        assertNull(saved.getLastErrorMessage());
    }

    @Test
    void aQuarterPastEveryHourIsFifteenMinutesApartAndAccepted() {
        ResponseEntity<ApiResponse<BackupSetting>> response = save("0 */15 * * * *");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(LocalDateTime.parse("2026-11-17T09:15:00"),
                response.getBody().getData().getNextBackupDate());
    }

    @Test
    void refusingTheScheduleLeavesTheRowThatWasStoredAlone() {
        savedRow = storedRow();
        savedRow.setNextBackupDate(LocalDateTime.parse("2026-11-20T03:00:00"));

        controller.updateSettings(customForm("not a cron"));

        verify(repository, never()).save(any(BackupSetting.class));
        assertEquals(LocalDateTime.parse("2026-11-20T03:00:00"), savedRow.getNextBackupDate(),
                "the row is refused before it is edited, so nothing it says is half-changed");
        assertEquals("WEEKLY", savedRow.getFrequency());
    }
}
