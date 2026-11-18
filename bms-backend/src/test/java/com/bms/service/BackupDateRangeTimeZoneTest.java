package com.bms.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V7: a date range the shop types is a range of the shop's days.
 *
 * <p>A range filter decides which sales, purchases and expenses leave the building
 * in a backup. Reading a stored instant on the machine's own clock moves a
 * transaction into a neighbouring day for every shop whose zone differs from the
 * PC's, so the night the shop asks for "the 17th" it gets the 17th plus whichever
 * hours the machine happens to disagree about.
 *
 * <p>These tests run with the JVM in UTC and the shop in {@code Asia/Yangon}
 * (UTC+6:30), which is the combination a packaged POS on a foreign-clock server
 * actually meets.
 */
class BackupDateRangeTimeZoneTest {

    private static final LocalDate THE_17TH = LocalDate.of(2026, 11, 17);
    private static final ZoneId YANGON = ZoneId.of("Asia/Yangon");

    private TimeZone machineZone;
    private BackupService service;

    @BeforeEach
    void theMachineRunsInUtcAndTheShopIsInYangon() {
        machineZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // The filter reads the shop clock and nothing else, so the repository layer
        // a backup normally walks through is left empty here.
        service = new BackupService(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "shopTimeService", new ShopTimeService(null, "Asia/Yangon"));
    }

    @AfterEach
    void theMachinesOwnClockComesBack() {
        TimeZone.setDefault(machineZone);
    }

    /** An instant that reads as {@code wallClock} on a Yangon wall clock. */
    private static Date atShopTime(String wallClock) {
        return Date.from(LocalDateTime.parse(wallClock).atZone(YANGON).toInstant());
    }

    private boolean askedForOnThe17th(Object date) {
        Boolean inside = ReflectionTestUtils.invokeMethod(service, "isDateInRange",
                date, THE_17TH, THE_17TH);
        return Boolean.TRUE.equals(inside);
    }

    @Test
    void aTransactionLateInTheShopDayIsStillOnThatDay() {
        assertTrue(askedForOnThe17th(atShopTime("2026-11-17T23:30:00")),
                "the last day of the range has to carry the whole of that shop day");
    }

    @Test
    void aTransactionInTheShopsSmallHoursIsOnTheShopsDayAndNotTheMachines() {
        // 01:00 in Yangon is 18:30 the previous evening in UTC. On the machine's
        // clock this sale falls off the front of the range and out of the backup.
        assertTrue(askedForOnThe17th(atShopTime("2026-11-17T01:00:00")),
                "a day the shop asked for by name cannot lose its own first hours");
    }

    @Test
    void aDayOutsideTheRangeStaysOutsideIt() {
        assertFalse(askedForOnThe17th(atShopTime("2026-11-18T01:00:00")),
                "the shop's next day is not this shop day");
        assertFalse(askedForOnThe17th(atShopTime("2026-11-16T23:30:00")));
    }

    @Test
    void aStoredWallClockTimestampIsReadAsTheShopDayItHolds() {
        assertTrue(askedForOnThe17th(LocalDateTime.of(2026, 11, 17, 23, 30)));
        assertFalse(askedForOnThe17th(LocalDateTime.of(2026, 11, 18, 0, 30)));
    }
}
