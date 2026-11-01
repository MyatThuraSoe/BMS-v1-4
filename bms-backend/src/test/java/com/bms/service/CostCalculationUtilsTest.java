package com.bms.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void removesPurchaseCostAtItsOwnUnitCost() {
        // 110 units averaged at 19.09 came from 100 @ 20 plus 10 @ 10.
        // Reversing the 10 @ 10 must restore 100 units at 20.00.
        BigDecimal remainingCost = CostCalculationUtils.calculateWeightedAverageCostAfterRemoval(
                new BigDecimal("19.09"), 110,
                new BigDecimal("10.00"), 10
        );

        assertEquals(new BigDecimal("20.00"), remainingCost);
    }

    @Test
    void removalToZeroStockClearsCost() {
        BigDecimal remainingCost = CostCalculationUtils.calculateWeightedAverageCostAfterRemoval(
                new BigDecimal("12.50"), 4,
                new BigDecimal("12.50"), 4
        );

        assertEquals(BigDecimal.ZERO, remainingCost);
    }

    @Test
    void removalNeverProducesNegativeCost() {
        // Removing expensive stock after cheaper units were sold can wipe out
        // more value than the average implies; cost must clamp at zero.
        BigDecimal remainingCost = CostCalculationUtils.calculateWeightedAverageCostAfterRemoval(
                new BigDecimal("5.00"), 10,
                new BigDecimal("20.00"), 5
        );

        assertEquals(0, remainingCost.compareTo(BigDecimal.ZERO));
    }
}
