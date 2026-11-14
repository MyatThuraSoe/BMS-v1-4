package com.bms.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Consumer;

public final class CostCalculationUtils {

    /** C7: stored costs keep scale 6; round only in DTOs and report output. */
    public static final int COST_SCALE = 6;

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
                .divide(BigDecimal.valueOf(totalQty), COST_SCALE, RoundingMode.HALF_UP);
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
        return calculateWeightedAverageCostAfterRemoval(currentCostPrice, currentStockQty,
                removedUnitCost, removedQty, null);
    }

    /**
     * C8: when the reversal empties the stock, keep the last known cost so the
     * next receipt averages from it instead of from zero. When a reversal would
     * drive the remaining value negative (other units were sold cheaper), keep
     * the last known cost rather than clamping silently, and raise a
     * reconciliation alert through {@code alertSink} so the anomaly is recorded.
     */
    public static BigDecimal calculateWeightedAverageCostAfterRemoval(
            BigDecimal currentCostPrice, int currentStockQty,
            BigDecimal removedUnitCost, int removedQty,
            Consumer<String> alertSink) {
        BigDecimal currentCost = currentCostPrice != null ? currentCostPrice : BigDecimal.ZERO;
        BigDecimal removedCost = removedUnitCost != null ? removedUnitCost : BigDecimal.ZERO;
        int remainingQty = currentStockQty - removedQty;

        if (remainingQty <= 0) {
            return currentCost.setScale(COST_SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal remainingValue = currentCost.multiply(BigDecimal.valueOf(currentStockQty))
                .subtract(removedCost.multiply(BigDecimal.valueOf(removedQty)));

        if (remainingValue.signum() < 0) {
            if (alertSink != null) {
                alertSink.accept("Purchase reversal drove remaining inventory value negative ("
                        + remainingValue.toPlainString() + ") across " + remainingQty
                        + " unit(s); kept last known cost " + currentCost.toPlainString());
            }
            return currentCost.setScale(COST_SCALE, RoundingMode.HALF_UP);
        }

        return remainingValue.divide(BigDecimal.valueOf(remainingQty), COST_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateProfit(BigDecimal unitPrice, BigDecimal costPriceAtSale, int quantity) {
        if (costPriceAtSale == null) {
            // C4: a missing cost must surface as an error, never as silent zero
            // profit that hides the fact the line was never costed.
            throw new com.bms.exception.BusinessException(
                    "Cannot compute profit: cost price at sale is missing");
        }
        if (unitPrice == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal profitPerUnit = unitPrice.subtract(costPriceAtSale);
        return profitPerUnit.multiply(BigDecimal.valueOf(quantity)).setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateCogs(BigDecimal costPriceAtSale, int quantity) {
        if (costPriceAtSale == null) {
            return BigDecimal.ZERO;
        }
        return costPriceAtSale.multiply(BigDecimal.valueOf(quantity)).setScale(COST_SCALE, RoundingMode.HALF_UP);
    }
}
