package com.bms.service;

import com.bms.entity.BackupSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Refusing a bad cron text on save only protects the schedule a shop sets from
 * now on. The rows that were stored while anything went unreadable still have to
 * be planned, and planned honestly: a tomorrow, a line in the log, and a note on
 * the settings page saying the backup it is getting is a daily one.
 */
class BackupCronFallbackTest {

    private static final LocalDateTime MONDAY_NINE = LocalDateTime.parse("2026-11-16T09:00:00");

    private ShopTimeService shopTime;
    private BackupService service;

    @BeforeEach
    void buildTheCalculator() {
        shopTime = mock(ShopTimeService.class);
        when(shopTime.now()).thenReturn(MONDAY_NINE);
        service = new BackupService(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "shopTimeService", shopTime);
    }

    private BackupSetting custom(String cronText) {
        BackupSetting setting = new BackupSetting();
        setting.setFrequency("CUSTOM");
        setting.setCustomCronExpression(cronText);
        return setting;
    }

    @Test
    void unreadableTextFromAnOldRowIsStillAnsweredWithTomorrow() {
        assertEquals(LocalDateTime.parse("2026-11-17T09:00:00"),
                service.calculateNextBackupDate(custom("0 0 2 * * L"), MONDAY_NINE));
    }

    @Test
    void unreadableTextIsRefusedWithAReasonTheShopCanRead() {
        assertEquals(BackupService.INVALID_CRON_TEXT_MESSAGE,
                BackupService.cronScheduleError("nonsense", MONDAY_NINE));
        assertEquals(BackupService.INVALID_CRON_TEXT_MESSAGE,
                BackupService.cronScheduleError(null, MONDAY_NINE),
                "a shop that picks Custom and types nothing has no schedule either");
    }

    @Test
    void aTextThatNeverFiresAgainIsRefusedAsNotValid() {
        assertEquals(BackupService.INVALID_CRON_TEXT_MESSAGE,
                BackupService.cronScheduleError("0 0 2 30 2 *", MONDAY_NINE),
                "the 30th of February is legal text with no next time");
        assertNull(BackupService.cronScheduleError("0 0 2 29 2 *", MONDAY_NINE),
                "the 29th of February only waits four years, which is a schedule and not a mistake");
    }

    @Test
    void aTextTighterThanAQuarterOfAnHourIsRefusedForItsOwnReason() {
        assertEquals(BackupService.TOO_OFTEN_CRON_TEXT_MESSAGE,
                BackupService.cronScheduleError("0 * * * * *", MONDAY_NINE));
        assertEquals(BackupService.TOO_OFTEN_CRON_TEXT_MESSAGE,
                BackupService.cronScheduleError("0 0,10 * * * *", MONDAY_NINE),
                "ten minutes apart for half the hour is ten minutes apart, even though the "
                        + "next run after any given one can be fifty minutes away");
        assertNull(BackupService.cronScheduleError("0 */15 * * * *", MONDAY_NINE),
                "exactly one run per fifteen minutes is what the rule allows");
        assertNull(BackupService.cronScheduleError("0 0 2 * * *", MONDAY_NINE),
                "a year between two runs is not a schedule that is too eager");
    }

    @Test
    void aNightlyTextIsAccepted() {
        assertNull(BackupService.cronScheduleError("0 0 2 * * *", MONDAY_NINE));
    }

    @Test
    void anOldRowWithUnreadableTextIsNotedWhereTheShopReadsIt() {
        assertEquals(BackupService.INVALID_CRON_MESSAGE,
                BackupService.invalidCronWarning(custom("nonsense"), MONDAY_NINE));
    }

    @Test
    void aRowThatSchedulesItselfHonestlyIsNotNoted() {
        assertNull(BackupService.invalidCronWarning(custom("0 0 2 * * *"), MONDAY_NINE));

        BackupSetting nightly = custom("0 0 2 * * *");
        nightly.setFrequency("DAILY");
        assertNull(BackupService.invalidCronWarning(nightly, MONDAY_NINE),
                "the stored text belongs to no schedule this row uses");
    }

    @Test
    void anOverlyFrequentOldTextIsHonouredRatherThanCalledInvalid() {
        // Rejected when typed, kept when already stored: it does fire, so the shop
        // is not being lied to about the schedule it has.
        assertNull(BackupService.invalidCronWarning(custom("0 * * * * *"), MONDAY_NINE));
    }
}
