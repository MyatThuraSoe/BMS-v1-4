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
}
