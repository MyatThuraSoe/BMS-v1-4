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
 * A monthly backup on the 31st was computed as the planned date plus one month,
 * and one month on from Jan 31 is Feb 28 because February has nothing later. The
 * next run then started from Feb 28 and gave Mar 28, Apr 28 and every month
 * after that: the shop that asked for the end of the month ended up with the
 * 28th forever, with nothing written down about the change.
 *
 * <p>So the day the shop first planned on is remembered, and each next date is
 * that day in the next month that has one, at the hour the shop chose.
 */
class BackupScheduleMonthlyAnchorTest {

    private static final LocalTime TWO_AM = LocalTime.of(2, 0);

    private ShopTimeService shopTime;
    private BackupService service;

    @BeforeEach
    void buildTheCalculator() {
        shopTime = mock(ShopTimeService.class);
        service = new BackupService(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "shopTimeService", shopTime);
    }

    private BackupSetting scheduled(String frequency, Integer anchorDayOfMonth) {
        BackupSetting setting = new BackupSetting();
        setting.setFrequency(frequency);
        setting.setScheduledTime(TWO_AM);
        setting.setAnchorDayOfMonth(anchorDayOfMonth);
        return setting;
    }

    /**
     * Runs the schedule on and on, as the shop does: each answer becomes the plan
     * for the next round, and the clock sits just after it so the run has plainly
     * happened.
     */
    private LocalDateTime[] thenEveryMonth(String frequency, Integer anchor, String firstPlan, int runs) {
        LocalDateTime[] answers = new LocalDateTime[runs];
        LocalDateTime plan = LocalDateTime.parse(firstPlan);
        for (int i = 0; i < runs; i++) {
            when(shopTime.now()).thenReturn(plan.plusMinutes(5));
            plan = service.calculateNextBackupDate(scheduled(frequency, anchor), plan);
            answers[i] = plan;
        }
        return answers;
    }

    @Test
    void aThirtyFirstAnchorComesBackAfterFebruary() {
        LocalDateTime[] runs = thenEveryMonth("MONTHLY", 31, "2026-01-31T02:00:00", 5);

        assertEquals(LocalDateTime.parse("2026-02-28T02:00:00"), runs[0], "a short month takes the last day it has");
        assertEquals(LocalDateTime.parse("2026-03-31T02:00:00"), runs[1], "the 31st is not given up after February");
        assertEquals(LocalDateTime.parse("2026-04-30T02:00:00"), runs[2]);
        assertEquals(LocalDateTime.parse("2026-05-31T02:00:00"), runs[3]);
        assertEquals(LocalDateTime.parse("2026-06-30T02:00:00"), runs[4]);
    }

    @Test
    void aLongDowntimeSkipsToTheNextDayTheAnchorFallsOn() {
        when(shopTime.now()).thenReturn(LocalDateTime.parse("2026-05-19T09:00:00"));

        assertEquals(LocalDateTime.parse("2026-05-31T02:00:00"),
                service.calculateNextBackupDate(scheduled("MONTHLY", 31), LocalDateTime.parse("2026-01-31T02:00:00")),
                "four months switched off still costs one catch-up run");
    }

    @Test
    void anAnchorInTheTwentiesKeepsItsOwnDayEveryMonth() {
        LocalDateTime[] runs = thenEveryMonth("MONTHLY", 21, "2026-01-21T02:00:00", 3);

        assertEquals(LocalDateTime.parse("2026-02-21T02:00:00"), runs[0]);
        assertEquals(LocalDateTime.parse("2026-03-21T02:00:00"), runs[1]);
        assertEquals(LocalDateTime.parse("2026-04-21T02:00:00"), runs[2]);
    }

    @Test
    void aLeapYearDayAnchorWaitsForALeapYearAndDoesNotMoveInBetween() {
        LocalDateTime[] runs = thenEveryMonth("YEARLY", 29, "2024-02-29T02:00:00", 4);

        assertEquals(LocalDateTime.parse("2025-02-28T02:00:00"), runs[0]);
        assertEquals(LocalDateTime.parse("2026-02-28T02:00:00"), runs[1]);
        assertEquals(LocalDateTime.parse("2027-02-28T02:00:00"), runs[2]);
        assertEquals(LocalDateTime.parse("2028-02-29T02:00:00"), runs[3], "the 29th comes back on its own");
    }

    @Test
    void aRowSavedBeforeThisFixUsesTheDayItWasAlreadyPlannedFor() {
        when(shopTime.now()).thenReturn(LocalDateTime.parse("2026-03-05T09:00:00"));

        assertEquals(LocalDateTime.parse("2026-03-28T02:00:00"),
                service.calculateNextBackupDate(scheduled("MONTHLY", null), LocalDateTime.parse("2026-02-28T02:00:00")),
                "an old row has no anchor, so its own planned day is the anchor");
    }
}
