package com.bms.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cash-drawer variance rules, kept in one place so the close screen, the API and
 * the audit trail all agree on what counts as a discrepancy worth flagging.
 */
public final class CashVarianceUtil {

    /**
     * Largest absolute difference between counted and expected cash that is
     * treated as ordinary counting noise.
     *
     * <p>This is expressed in currency units rather than a percentage: a
     * percentage of a near-empty drawer is a few cents, which would flag every
     * quiet shift, while a percentage of a busy one is loose enough to hide a
     * real shortage. A flat amount behaves the same way on both.
     */
    public static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("10.00");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private CashVarianceUtil() {
    }

    /**
     * Expected drawer = opening float + cash sales − refunds.
     *
     * <p>Refund cash comes straight off the drawer, so it is subtracted even
     * when it exceeds the float and sales, which pushes the expected amount
     * negative. That is the honest reading of an emptied drawer, and it keeps
     * the variance a straight counted-minus-expected difference.
     */
    public static BigDecimal expectedDrawer(BigDecimal openingAmount,
                                           BigDecimal cashSalesTotal,
                                           BigDecimal returnsTotal) {
        return nullSafe(openingAmount)
                .add(nullSafe(cashSalesTotal))
                .subtract(nullSafe(returnsTotal));
    }

    /**
     * Variance between the cash actually counted and the cash expected.
     * Positive is a surplus, negative a shortage.
     */
    public static BigDecimal variance(BigDecimal countedAmount, BigDecimal expectedAmount) {
        return nullSafe(countedAmount).subtract(nullSafe(expectedAmount));
    }

    /**
     * True when the difference is outside ordinary counting noise.
     */
    public static boolean exceedsTolerance(BigDecimal variance, BigDecimal tolerance) {
        BigDecimal limit = tolerance == null ? DEFAULT_TOLERANCE : tolerance.abs();
        return nullSafe(variance).abs().compareTo(limit) > 0;
    }

    /**
     * Human-readable percentage of the expected amount that the variance
     * represents, e.g. "12.5%". Returns null when expected is zero, where a
     * percentage would be meaningless.
     */
    public static BigDecimal variancePercent(BigDecimal variance, BigDecimal expectedAmount) {
        BigDecimal expected = nullSafe(expectedAmount);
        if (expected.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return nullSafe(variance)
                .multiply(HUNDRED)
                .divide(expected.abs(), 1, RoundingMode.HALF_UP);
    }

    /**
     * Audit-trail suffix describing how far the variance sits from the
     * tolerance, so a log reader can tell a 0.02 rounding wobble from a drawer
     * that is meaningfully out. Returns an empty string when within tolerance.
     */
    public static String describeVariance(BigDecimal variance, BigDecimal expectedAmount) {
        return describeVariance(variance, expectedAmount, DEFAULT_TOLERANCE);
    }

    public static String describeVariance(BigDecimal variance, BigDecimal expectedAmount, BigDecimal tolerance) {
        if (!exceedsTolerance(variance, tolerance)) {
            return "";
        }
        BigDecimal percent = variancePercent(variance, expectedAmount);
        return " | EXCEEDS TOLERANCE: " + nullSafe(variance).abs()
                + " vs " + (tolerance == null ? DEFAULT_TOLERANCE : tolerance.abs())
                + (percent == null ? "" : " (" + percent + "% of expected)");
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}