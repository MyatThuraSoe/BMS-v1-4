package com.bms.service;

import com.bms.entity.BackupSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A cron line is a promise about the clock, not a queue to drain. When the shop
 * was switched off for three days the plan it left behind is three days old, and
 * counting the next run from that plan hands back another past time: the
 * scheduler runs it, plans another past time, and runs again on its next look,
 * until the schedule has worked its way back to today — three backups for three
 * days off, and a shop that cannot tell which of them it asked for.
 */
class BackupScheduleDowntimeTest {

    private static final String EVERY_DAY_AT_TWO = "0 0 2 * * *";

    private ShopTimeService shopTime;
    private BackupService service;

    @BeforeEach
    void buildTheCalculator() {
        shopTime = mock(ShopTimeService.class);
        // The schedule reads the shop clock, the frequency and the cron text and
        // nothing else, so the rest of what a backup needs is left empty here.
        service = new BackupService(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "shopTimeService", shopTime);
    }

    private BackupSetting cron(String expression) {
        BackupSetting setting = new BackupSetting();
        setting.setFrequency("CUSTOM");
        setting.setCustomCronExpression(expression);
        return setting;
    }

    private LocalDateTime nextAfter(String shopClock, String cronText, String planned) {
        when(shopTime.now()).thenReturn(LocalDateTime.parse(shopClock));
        return service.calculateNextBackupDate(cron(cronText), LocalDateTime.parse(planned));
    }

    @Test
    void threeDaysOffIsOneRunAndATimeInTheFuture() {
        LocalDateTime next = nextAfter("2026-11-17T09:15:00", EVERY_DAY_AT_TWO, "2026-11-14T02:00:00");

        assertEquals(LocalDateTime.parse("2026-11-18T02:00:00"), next,
                "the stale plan is skipped, not caught up with");
        assertTrue(next.isAfter(LocalDateTime.parse("2026-11-17T09:15:00")),
                "a plan in the past runs again on the very next look");
    }

    @Test
    void theAnswerCountsFromTheClockAndNotFromTheStalePlan() {
        // Every hour at half past: the shop comes back at a quarter past nine, so
        // the next promise is half past nine today — not half past one in the
        // morning of the day it was switched off, which is what counting from the
        // stale plan hands back.
        assertEquals(LocalDateTime.parse("2026-11-17T09:30:00"),
                nextAfter("2026-11-17T09:15:00", "0 30 * * * *", "2026-11-13T00:30:00"));
    }

    @Test
    void aScheduleThatWasNeverMissedDoesNotMove() {
        assertEquals(LocalDateTime.parse("2026-11-18T02:00:00"),
                nextAfter("2026-11-17T02:07:00", EVERY_DAY_AT_TWO, "2026-11-17T02:00:00"));
    }

    @Test
    void aTimeThatNeverArrivesIsAnsweredWithTomorrowRatherThanNothing() {
        // The 30th of February: the expression is legal, its next time does not
        // exist, and a shop must still be told when to expect its backup.
        LocalDateTime next = nextAfter("2026-11-17T09:15:00", "0 0 2 30 2 *", "2026-11-16T02:00:00");

        assertEquals(LocalDateTime.parse("2026-11-18T09:15:00"), next);
    }
}
