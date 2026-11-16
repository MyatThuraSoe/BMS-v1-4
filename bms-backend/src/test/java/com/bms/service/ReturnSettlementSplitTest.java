package com.bms.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N1: a return refunds several lines, but its debt reduction and its refund are
 * decided once for the whole return. Splitting them has to be exact, so each
 * ledger line carries only its own share of the money.
 */
class ReturnSettlementSplitTest {

    /** Replays the loop SaleReturnService uses so the parts can be read in order. */
    private static List<BigDecimal> split(String debtReduction, String totalReturnAmount, String... lineTotals) {
        BigDecimal reduction = new BigDecimal(debtReduction);
        BigDecimal total = new BigDecimal(totalReturnAmount);
        BigDecimal unallocated = reduction;
        List<BigDecimal> parts = new ArrayList<>();
        for (int i = 0; i < lineTotals.length; i++) {
            BigDecimal lineTotal = new BigDecimal(lineTotals[i]);
            BigDecimal lineDebt = SaleReturnService.debtForLine(reduction, total, lineTotal, unallocated,
                    i == lineTotals.length - 1);
            unallocated = unallocated.subtract(lineDebt);
            parts.add(lineDebt);
        }
        return parts;
    }

    /**
     * M5: a line may never be handed more debt than it is worth, otherwise
     * lineTotal - lineDebt is negative and the refund on that line is booked with
     * the wrong sign. The parts must also still add up to the whole reduction.
     */
    private static void assertSplitStaysWithinItsLines(String debtReduction, String... lineTotals) {
        BigDecimal reduction = new BigDecimal(debtReduction);
        BigDecimal total = BigDecimal.ZERO;
        List<BigDecimal> lines = new ArrayList<>();
        for (String lineTotal : lineTotals) {
            BigDecimal value = new BigDecimal(lineTotal);
            total = total.add(value);
            lines.add(value);
        }
        List<BigDecimal> parts = SaleReturnService.debtForLines(reduction, total, lines);
        assertEquals(lines.size(), parts.size(), "one debt part per line");
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < parts.size(); i++) {
            BigDecimal debt = parts.get(i);
            assertTrue(debt.signum() >= 0,
                    "line " + i + " of " + lineTotals[i] + " was given negative debt " + debt);
            assertTrue(debt.compareTo(lines.get(i)) <= 0, "line " + i + " of " + lineTotals[i]
                    + " carries " + debt + " of debt, which leaves a refund of "
                    + lines.get(i).subtract(debt) + " for it to pay");
            sum = sum.add(debt);
        }
        assertEquals(0, reduction.compareTo(sum),
                "the lines must hand back exactly the " + reduction + " debt reduction that was split, got " + sum);
    }

    private static void assertParts(List<BigDecimal> parts, String debtReduction, String... expected) {
        assertEquals(expected.length, parts.size(), "part count, got " + parts);
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < expected.length; i++) {
            BigDecimal want = new BigDecimal(expected[i]);
            assertEquals(0, want.compareTo(parts.get(i)), "part " + i + " was " + parts.get(i));
            sum = sum.add(want);
        }
        assertEquals(0, new BigDecimal(debtReduction).compareTo(sum),
                "the lines must hand back exactly the debt reduction that was split");
    }

    @Test
    void debtIsSharedProportionallyAndTheLastLineAbsorbsTheRemainder() {
        // Debt 20 over a 100 return with lines of 60 and 40.
        assertParts(split("20.00", "100.00", "60.00", "40.00"), "20.00", "12.00", "8.00");
    }

    @Test
    void repeatedRoundingNeverLeaksACentOutOfTheDebt() {
        assertParts(split("10.00", "3.00", "1.00", "1.00", "1.00"), "10.00", "3.33", "3.33", "3.34");
    }

    @Test
    void roundingNeverTakesMoreDebtThanRemains() {
        // Each early line wants 0.015, which rounds up to 0.02: without a clamp
        // the fifth line would be asked for money that is already gone.
        assertParts(split("0.09", "6.00", "1.00", "1.00", "1.00", "1.00", "1.00", "1.00"),
                "0.09", "0.02", "0.02", "0.02", "0.02", "0.01", "0.00");
    }

    @Test
    void aReturnWithNoDebtReductionAllocatesNothing() {
        assertParts(split("0.00", "60.00", "60.00"), "0.00", "0.00");
        assertParts(split("0.00", "100.00", "60.00", "40.00"), "0.00", "0.00", "0.00");
    }

    @Test
    void aZeroValuedReturnCannotDivideAndStillReceivesTheRemainder() {
        assertParts(split("0.00", "0.00", "0.00", "0.00"), "0.00", "0.00", "0.00");
    }

    @Test
    void refundIsWhateverTheLineTotalHasLeftAfterDebt() {
        // A 60 line carrying 20 of debt leaves 40 to refund.
        List<BigDecimal> debt = split("20.00", "60.00", "60.00");
        assertEquals(0, new BigDecimal("40.00").compareTo(new BigDecimal("60.00").subtract(debt.get(0))));
    }

    @Test
    void sevenUnevenLinesOfACreditReturnShareTheDebtWithoutLeavingACentBehind() {
        // M5: the shape a real till sees - seven prices that do not divide evenly
        // into the debt reduction, ending on a line worth one cent.
        assertSplitStaysWithinItsLines("17.99", "3.33", "1.67", "9.99", "0.51", "4.44", "2.22", "0.01");
    }

    @Test
    void theLastLineIsNeverAskedForMoreDebtThanItIsWorth() {
        // One cent more on the sixth line and every earlier share rounds down, so
        // the remainder the last line absorbs outgrows its own 0.01 total.
        assertSplitStaysWithinItsLines("17.99", "3.33", "1.67", "9.99", "0.51", "4.44", "2.23", "0.01");
    }

    @Test
    void oneLineAndNoDebtAndAWholeReturnAllStayInsideTheirLines() {
        assertSplitStaysWithinItsLines("20.00", "60.00");
        assertSplitStaysWithinItsLines("0.00", "3.33", "1.67", "9.99");
        assertSplitStaysWithinItsLines("14.99", "3.33", "1.67", "9.99");
    }
}
