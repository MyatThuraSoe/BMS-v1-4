package com.bms.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CashVarianceUtilTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void expectedDrawerAddsSalesAndSubtractsRefunds() {
        assertEquals(bd("450.00"),
                CashVarianceUtil.expectedDrawer(bd("100.00"), bd("500.00"), bd("150.00")));
    }

    @Test
    void expectedDrawerGoesNegativeWhenRefundsExceedFloatAndSales() {
        // The zero-opening case that motivated this: a refund larger than the
        // float and the day's cash sales empties the drawer and then some.
        assertEquals(bd("-50.00"),
                CashVarianceUtil.expectedDrawer(bd("0.00"), bd("30.00"), bd("80.00")));
    }

    @Test
    void expectedDrawerTreatsZeroOpeningAsValid() {
        assertEquals(bd("0.00"),
                CashVarianceUtil.expectedDrawer(bd("0.00"), bd("0.00"), bd("0.00")));
    }

    @Test
    void expectedDrawerTreatsNullsAsZero() {
        assertEquals(bd("75.00"),
                CashVarianceUtil.expectedDrawer(null, bd("75.00"), null));
    }

    @Test
    void varianceIsPositiveForSurplusAndNegativeForShortfall() {
        assertEquals(bd("20.00"), CashVarianceUtil.variance(bd("220.00"), bd("200.00")));
        assertEquals(bd("-20.00"), CashVarianceUtil.variance(bd("180.00"), bd("200.00")));
    }

    @Test
    void toleranceIsInclusiveAtTheBoundary() {
        // Exactly at the limit is still "within tolerance"; only strictly more
        // is flagged, so a round 10.00 overage does not raise a false alarm.
        assertFalse(CashVarianceUtil.exceedsTolerance(bd("10.00"), CashVarianceUtil.DEFAULT_TOLERANCE));
        assertFalse(CashVarianceUtil.exceedsTolerance(bd("-10.00"), CashVarianceUtil.DEFAULT_TOLERANCE));
        assertTrue(CashVarianceUtil.exceedsTolerance(bd("10.01"), CashVarianceUtil.DEFAULT_TOLERANCE));
        assertTrue(CashVarianceUtil.exceedsTolerance(bd("-10.01"), CashVarianceUtil.DEFAULT_TOLERANCE));
    }

    @Test
    void toleranceFlagsLargeShortfallFromARefundLargerThanTheDrawer() {
        BigDecimal expected = CashVarianceUtil.expectedDrawer(bd("0.00"), bd("30.00"), bd("80.00"));
        BigDecimal variance = CashVarianceUtil.variance(bd("0.00"), expected);
        assertTrue(CashVarianceUtil.exceedsTolerance(variance, CashVarianceUtil.DEFAULT_TOLERANCE));
    }

    @Test
    void nullToleranceFallsBackToTheDefault() {
        assertTrue(CashVarianceUtil.exceedsTolerance(bd("50.00"), null));
        assertFalse(CashVarianceUtil.exceedsTolerance(bd("5.00"), null));
    }

    @Test
    void nullVarianceIsWithinTolerance() {
        assertFalse(CashVarianceUtil.exceedsTolerance(null, CashVarianceUtil.DEFAULT_TOLERANCE));
    }

    @Test
    void variancePercentIsRelativeToExpectedAmount() {
        assertEquals(bd("12.5"), CashVarianceUtil.variancePercent(bd("25.00"), bd("200.00")));
        assertEquals(bd("-12.5"), CashVarianceUtil.variancePercent(bd("-25.00"), bd("200.00")));
    }

    @Test
    void variancePercentIsNullWhenExpectedIsZero() {
        assertNull(CashVarianceUtil.variancePercent(bd("25.00"), bd("0.00")));
    }

    @Test
    void describeVarianceIsEmptyWithinTolerance() {
        assertEquals("", CashVarianceUtil.describeVariance(bd("5.00"), bd("200.00")));
    }

    @Test
    void describeVarianceFlagsOverToleranceWithAmountAndPercent() {
        String description = CashVarianceUtil.describeVariance(bd("-25.00"), bd("200.00"));
        assertTrue(description.contains("EXCEEDS TOLERANCE"));
        assertTrue(description.contains("25.00"));
        assertTrue(description.contains("10.00"));
        assertTrue(description.contains("12.5%"));
    }

    @Test
    void describeVarianceOmitsPercentWhenExpectedIsZero() {
        String description = CashVarianceUtil.describeVariance(bd("-25.00"), bd("0.00"));
        assertTrue(description.contains("EXCEEDS TOLERANCE"));
        assertFalse(description.contains("%"));
    }
}