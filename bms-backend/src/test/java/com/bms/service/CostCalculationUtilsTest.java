package com.bms.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostCalculationUtilsTest {

    @Test
    void calculatesWeightedAverageCostCorrectly() {
        BigDecimal newCost = CostCalculationUtils.calculateWeightedAverageCost(
                new BigDecimal("10.00"), 10,
                new BigDecimal("12.00"), 20
        );

        assertEquals(new BigDecimal("11.33"), newCost.setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void calculatesProfitForSaleItemCorrectly() {
        BigDecimal profit = CostCalculationUtils.calculateProfit(
                new BigDecimal("15.00"),
                new BigDecimal("11.33"),
                2
        );

        assertEquals(new BigDecimal("7.34"), profit.setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void profitThrowsOnNullCostInsteadOfSilentlyReportingZero() {
        // C4: a missing cost must surface as an error, never as fake profit.
        org.junit.jupiter.api.Assertions.assertThrows(com.bms.exception.BusinessException.class,
                () -> CostCalculationUtils.calculateProfit(new BigDecimal("15.00"), null, 2));
    }

    @Test
    void removesPurchaseCostAtItsOwnUnitCost() {
        // 110 units from 100 @ 20 plus 10 @ 10 -> average 2100/110. Reversing the
        // 10 @ 10 must restore 100 units at 20.00. C7 keeps scale 6 so the rounded
        // average no longer masks the residual.
        BigDecimal average = new BigDecimal("2100").divide(new BigDecimal("110"), 6, RoundingMode.HALF_UP);
        BigDecimal remainingCost = CostCalculationUtils.calculateWeightedAverageCostAfterRemoval(
                average, 110, new BigDecimal("10.00"), 10
        );

        assertEquals(0, remainingCost.compareTo(new BigDecimal("20.00")));
    }

    @Test
    void c7_weightedAverageKeepsSixDecimalPrecision() {
        // Acceptance: 26 units bought for a total of 8.7837 -> unit cost
        // 8.7837 / 26 = 0.337835 at scale 6, and qty x cost reconciles with spend.
        BigDecimal unitCost = new BigDecimal("8.7837")
                .divide(BigDecimal.valueOf(26), 6, RoundingMode.HALF_UP);
        assertEquals(0, unitCost.compareTo(new BigDecimal("0.337835")));

        BigDecimal average = CostCalculationUtils.calculateWeightedAverageCost(
                BigDecimal.ZERO, 0, unitCost, 26
        );

        assertEquals(0, average.compareTo(new BigDecimal("0.337835")));
        assertEquals(6, average.scale());
        BigDecimal reconciled = average.multiply(BigDecimal.valueOf(26));
        assertTrue(reconciled.subtract(new BigDecimal("8.7837")).abs()
                .compareTo(new BigDecimal("0.00005")) <= 0);
    }

    @Test
    void c8_removalToZeroStockKeepsLastKnownCost() {
        // Acceptance: removing all units leaves cost at the last known value so
        // the next receipt averages from it, not from zero.
        BigDecimal remainingCost = CostCalculationUtils.calculateWeightedAverageCostAfterRemoval(
                new BigDecimal("12.50"), 4, new BigDecimal("12.50"), 4
        );

        assertEquals(0, remainingCost.compareTo(new BigDecimal("12.50")));
    }

    @Test
    void c8_nextReceiptAveragesFromLastKnownCostAfterEmptying() {
        BigDecimal lastKnown = CostCalculationUtils.calculateWeightedAverageCostAfterRemoval(
                new BigDecimal("12.50"), 4, new BigDecimal("12.50"), 4
        );
        BigDecimal next = CostCalculationUtils.calculateWeightedAverageCost(
                lastKnown, 0, new BigDecimal("20.00"), 10
        );
        // 0 units owned -> the receipt cost becomes the new average.
        assertEquals(0, next.compareTo(new BigDecimal("20.00")));
    }

    @Test
    void c8_negativeRemainingValueKeepsLastKnownCostAndAlerts() {
        // Removing expensive stock after cheaper units were sold wipes out more
        // value than the average implies. Keep the last known cost (never
        // negative, never a silent zero) and raise a reconciliation alert.
        List<String> alerts = new ArrayList<>();
        BigDecimal remainingCost = CostCalculationUtils.calculateWeightedAverageCostAfterRemoval(
                new BigDecimal("5.00"), 10, new BigDecimal("20.00"), 5, alerts::add
        );

        assertEquals(0, remainingCost.compareTo(new BigDecimal("5.00")));
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).toLowerCase().contains("negative"));
    }

    @Test
    void c8_normalRemovalDoesNotAlert() {
        List<String> alerts = new ArrayList<>();
        CostCalculationUtils.calculateWeightedAverageCostAfterRemoval(
                new BigDecimal("20.00"), 100, new BigDecimal("10.00"), 10, alerts::add
        );
        assertTrue(alerts.isEmpty());
    }
}
