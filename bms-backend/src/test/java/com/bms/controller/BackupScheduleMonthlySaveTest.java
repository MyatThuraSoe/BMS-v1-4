package com.bms.controller;

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
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Saving the schedule is where the day of the month a monthly backup belongs to
 * gets written down, and where it can go wrong: counted from Jan 31 the first
 * planned date is Feb 28 because February has no 31st, so a shop that notes the
 * answer instead of the day it started from ends up on the 28th forever.
 */
class BackupScheduleMonthlySaveTest {

    private BackupSetting savedRow;
    private ShopTimeService shopTime;
    private BackupController controller;

    @BeforeEach
    void wireTheSettingsPage() {
        shopTime = mock(ShopTimeService.class);
        // The schedule reads the shop clock, the frequency and the settings row;
        // the format and encryption switches the reply carries are read from the
        // two services below, and every repository a real backup writes to is
        // left empty here.
        BackupService backupService = new BackupService(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                mock(SystemSettingRepository.class), mock(DatabaseSnapshotService.class));
        ReflectionTestUtils.setField(backupService, "shopTimeService", shopTime);

        BackupSettingRepository repository = mock(BackupSettingRepository.class);
        when(repository.findFirstByOrderByIdAsc()).thenAnswer(call -> Optional.of(savedRow));
        when(repository.save(any(BackupSetting.class))).thenAnswer(call -> call.getArgument(0));

        controller = new BackupController(repository, backupService,
                mock(GoogleDriveService.class), mock(DataExportService.class), shopTime);
    }

    /** The row as the shop last left it. */
    private BackupSetting storedRow(String frequency, LocalTime time, Integer anchor, LocalDateTime next) {
        BackupSetting row = new BackupSetting();
        row.setEnabled(true);
        row.setFrequency(frequency);
        row.setScheduledTime(time);
        row.setAnchorDayOfMonth(anchor);
        row.setNextBackupDate(next);
        return row;
    }

    /** What the settings page sends: its own object, as the real form does. */
    private BackupSetting form(String frequency, LocalTime time) {
        BackupSetting request = new BackupSetting();
        request.setEnabled(true);
        request.setFrequency(frequency);
        request.setScheduledTime(time);
        return request;
    }

    private BackupSetting save(BackupSetting request) {
        ResponseEntity<com.bms.dto.response.ApiResponse<BackupSetting>> response =
                controller.updateSettings(request);
        return response.getBody().getData();
    }

    @Test
    void switchingToMonthlyOnThe31stAnchorsToThe31stAndNotToFebruary() {
        when(shopTime.now()).thenReturn(LocalDateTime.parse("2026-01-31T09:00:00"));
        savedRow = storedRow("WEEKLY", null, null, null);

        BackupSetting result = save(form("MONTHLY", null));

        assertEquals(31, result.getAnchorDayOfMonth(),
                "the day the shop started from is the day it means, not the clamped answer");
        assertEquals(LocalDateTime.parse("2026-02-28T09:00:00"), result.getNextBackupDate());
    }

    @Test
    void anAnchorAlreadyChosenSurvivesAnUnrelatedSave() {
        when(shopTime.now()).thenReturn(LocalDateTime.parse("2026-03-05T09:00:00"));
        savedRow = storedRow("MONTHLY", LocalTime.of(2, 0), 31, LocalDateTime.parse("2026-03-31T02:00:00"));

        BackupSetting result = save(form("MONTHLY", LocalTime.of(2, 0)));

        assertEquals(31, result.getAnchorDayOfMonth());
        assertEquals(LocalDateTime.parse("2026-03-31T02:00:00"), result.getNextBackupDate(),
                "a run already promised in the future is not jumped ahead of");
    }

    @Test
    void changingTheFrequencyNotesTheNewScheduleOwnDay() {
        when(shopTime.now()).thenReturn(LocalDateTime.parse("2026-05-17T09:00:00"));
        savedRow = storedRow("MONTHLY", LocalTime.of(2, 0), 31, LocalDateTime.parse("2026-05-10T02:00:00"));

        BackupSetting result = save(form("YEARLY", LocalTime.of(2, 0)));

        assertEquals(17, result.getAnchorDayOfMonth(),
                "a yearly schedule counted from today belongs to today, not to the month it replaces");
        assertEquals(LocalDateTime.parse("2027-05-17T02:00:00"), result.getNextBackupDate());
    }

    @Test
    void aDailyScheduleLeavesTheAnchorToTheCalendarThatReadsIt() {
        when(shopTime.now()).thenReturn(LocalDateTime.parse("2026-01-31T09:00:00"));
        savedRow = storedRow("MONTHLY", LocalTime.of(2, 0), 31, LocalDateTime.parse("2026-01-31T02:00:00"));

        BackupSetting result = save(form("DAILY", LocalTime.of(2, 0)));

        assertEquals(31, result.getAnchorDayOfMonth(),
                "a daily schedule never reads the anchor, so switching to it does not rewrite one");
        assertEquals(LocalDateTime.parse("2026-02-01T02:00:00"), result.getNextBackupDate());
    }
}
