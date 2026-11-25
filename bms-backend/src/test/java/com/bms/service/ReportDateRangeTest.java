package com.bms.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Audit item 16: inclusive WEEK/MONTH ranges must not contain an extra day. */
class ReportDateRangeTest {

    private ReportService reportService;
    private final LocalDate endDate = LocalDate.of(2026, 11, 25);

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    @Test
    void weekRangeContainsSevenDaysIncludingTheEndDate() {
        LocalDate start = ReflectionTestUtils.invokeMethod(reportService, "resolveStartDate", "WEEK", endDate);

        assertEquals(LocalDate.of(2026, 11, 19), start);
        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(start, endDate) + 1);
    }

    @Test
    void monthRangeContainsOneRollingMonthIncludingTheEndDate() {
        LocalDate start = ReflectionTestUtils.invokeMethod(reportService, "resolveStartDate", "MONTH", endDate);

        assertEquals(LocalDate.of(2026, 10, 26), start);
        assertEquals(31, java.time.temporal.ChronoUnit.DAYS.between(start, endDate) + 1);
    }

    @Test
    void previousPeriodHasTheSameLengthAndEndsTheDayBeforeTheCurrentRange() {
        LocalDate start = ReflectionTestUtils.invokeMethod(reportService, "resolveStartDate", "WEEK", endDate);
        LocalDate[] previous = ReflectionTestUtils.invokeMethod(
                reportService, "resolveComparisonRange", "WEEK", "PREVIOUS_PERIOD", start, endDate);

        assertArrayEquals(new LocalDate[]{ LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 18) }, previous);
        assertEquals(java.time.temporal.ChronoUnit.DAYS.between(start, endDate),
                java.time.temporal.ChronoUnit.DAYS.between(previous[0], previous[1]));
    }
}
