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

    /**
     * Weighted-average cost of the stock that remains after reversing one
     * purchase line. The removed units are valued at the price actually paid
     * on that line (not the current average), and the denominator is the
     * post-removal stock.
     */
    public static BigDecimal calculateWeightedAverageCostAfterRemoval(
            BigDecimal currentCostPrice, int currentStockQty,
            BigDecimal removedUnitCost, int removedQty) {
        int remainingQty = currentStockQty - removedQty;
        if (remainingQty <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal currentCost = currentCostPrice != null ? currentCostPrice : BigDecimal.ZERO;
        BigDecimal removedCost = removedUnitCost != null ? removedUnitCost : BigDecimal.ZERO;
        BigDecimal remainingValue = currentCost.multiply(BigDecimal.valueOf(currentStockQty))
                .subtract(removedCost.multiply(BigDecimal.valueOf(removedQty)));

        // A reversal can legitimately wipe out more value than the average
        // implies when other units were sold; never report a negative cost.
        if (remainingValue.signum() < 0) {
            remainingValue = BigDecimal.ZERO;
        }

        return remainingValue.divide(BigDecimal.valueOf(remainingQty), 2, RoundingMode.HALF_UP);
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
