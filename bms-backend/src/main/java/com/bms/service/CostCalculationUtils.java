package com.bms.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class CostCalculationUtils {

    private CostCalculationUtils() {
    }

    public static BigDecimal calculateWeightedAverageCost(BigDecimal oldCostPrice, int oldStockQty,
                                                          BigDecimal purchasedUnitCost, int purchasedQty) {
        BigDecimal oldCostPriceValue = oldCostPrice != null ? oldCostPrice : BigDecimal.ZERO;
        BigDecimal totalOldValue = oldCostPriceValue.multiply(BigDecimal.valueOf(oldStockQty));
        BigDecimal totalNewValue = purchasedUnitCost.multiply(BigDecimal.valueOf(purchasedQty));
        int totalQty = oldStockQty + purchasedQty;

        if (totalQty <= 0) {
            return purchasedUnitCost != null ? purchasedUnitCost : BigDecimal.ZERO;
        }

        return totalOldValue.add(totalNewValue)
                .divide(BigDecimal.valueOf(totalQty), 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateProfit(BigDecimal unitPrice, BigDecimal costPriceAtSale, int quantity) {
        if (unitPrice == null || costPriceAtSale == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal profitPerUnit = unitPrice.subtract(costPriceAtSale);
        return profitPerUnit.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateCogs(BigDecimal costPriceAtSale, int quantity) {
        if (costPriceAtSale == null) {
            return BigDecimal.ZERO;
        }
        return costPriceAtSale.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }
}
