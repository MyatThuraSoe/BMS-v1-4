package com.bms.service;

import com.bms.entity.BackupSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Q6: a backup keeps the time it was promised at.
 *
 * <p>The schedule used to be recomputed from the moment a run finished. The scheduler only
 * looks every ten minutes and a backup takes time of its own, so a daily 02:00 backup came
 * back as 02:07, and 02:07 came back as 02:14 — weeks of that moved the shop's backup into
 * opening hours, which is when the counter is busiest and the files are largest.
 *
 * <p>So the next slot is counted from the slot that was planned, in whole periods, at the
 * time of day the shop chose in the shop's own clock.
 */
class BackupScheduleAnchorTest {

    private static final LocalTime TWO_AM = LocalTime.of(2, 0);

    private ShopTimeService shopTime;
    private BackupService service;

    @BeforeEach
    void buildTheCalculator() {
        shopTime = mock(ShopTimeService.class);
        // The schedule reads the shop clock and the settings row and nothing else, so the
        // repository layer a backup normally needs is left empty here.
        service = new BackupService(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "shopTimeService", shopTime);
    }

    private void theShopClockSays(String moment) {
        when(shopTime.now()).thenReturn(LocalDateTime.parse(moment));
    }

    private BackupSetting scheduled(String frequency, LocalTime scheduledTime) {
        BackupSetting setting = new BackupSetting();
        setting.setFrequency(frequency);
        setting.setScheduledTime(scheduledTime);
        return setting;
    }

    private LocalDateTime next(String frequency, LocalTime scheduledTime, String planned) {
        return service.calculateNextBackupDate(scheduled(frequency, scheduledTime), LocalDateTime.parse(planned));
    }

    @Test
    void aLateFinishDoesNotMoveTomorrow() {
        theShopClockSays("2026-11-17T02:07:00");

        assertEquals(LocalDateTime.parse("2026-11-18T02:00:00"), next("DAILY", TWO_AM, "2026-11-17T02:00:00"));
    }

    @Test
    void threeDaysSwitchedOffIsOneCatchUpRunAndNotThree() {
        theShopClockSays("2026-11-17T09:15:00");

        assertEquals(LocalDateTime.parse("2026-11-18T02:00:00"), next("DAILY", TWO_AM, "2026-11-14T02:00:00"));
    }

    @Test
    void aWeeklyKeepsItsDayAndItsHour() {
        theShopClockSays("2026-11-17T02:31:00");

        assertEquals(LocalDateTime.parse("2026-11-24T02:00:00"), next("WEEKLY", TWO_AM, "2026-11-17T02:00:00"));
    }

    @Test
    void aMonthlyOnTheThirtyFirstLandsOnTheEndOfAShortMonth() {
        theShopClockSays("2026-01-31T02:05:00");

        assertEquals(LocalDateTime.parse("2026-02-28T02:00:00"), next("MONTHLY", TWO_AM, "2026-01-31T02:00:00"));
    }

    @Test
    void aChosenTimeSurvivesARetriedRun() {
        // The hour a retry ran at says nothing about when the shop wants its backup.
        theShopClockSays("2026-11-17T03:40:00");

        assertEquals(LocalDateTime.parse("2026-11-18T02:00:00"), next("DAILY", TWO_AM, "2026-11-17T03:00:00"));
    }

    @Test
    void noChosenTimeKeepsTheMomentAutomationStarted() {
        theShopClockSays("2026-11-17T15:45:00");

        assertEquals(LocalDateTime.parse("2026-11-18T15:45:00"), next("DAILY", null, "2026-11-17T15:45:00"));
    }

    @Test
    void aCronScheduleIsLeftToItsOwnExpression() {
        theShopClockSays("2026-11-17T02:07:00");
        BackupSetting setting = scheduled("CUSTOM", TWO_AM);
        setting.setCustomCronExpression("0 0 3 * * *");

        assertEquals(LocalDateTime.parse("2026-11-17T03:00:00"),
                service.calculateNextBackupDate(setting, LocalDateTime.parse("2026-11-17T02:00:00")));
    }
}
