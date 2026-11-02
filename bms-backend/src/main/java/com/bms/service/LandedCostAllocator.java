package com.bms.service;

import com.bms.entity.PurchaseExpense;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure landed-cost allocation engine.
 *
 * <p>Each expense (freight, customs, insurance, ...) is spread across the
 * purchase lines using its own allocation method. The result is a map of
 * purchase-item id to landed unit cost. Keeping this logic pure makes it easy
 * to test and guarantees the same math is used for WAC and for the PO display.</p>
 */
public final class LandedCostAllocator {

    private static final int COST_SCALE = 4;

    private LandedCostAllocator() {
    }

    public static class Line {
        private final Long id;
        private final BigDecimal unitCost;
        private final int quantity;
        private final BigDecimal weight;

        public Line(Long id, BigDecimal unitCost, int quantity, BigDecimal weight) {
            this.id = id;
            this.unitCost = unitCost != null ? unitCost : BigDecimal.ZERO;
            this.quantity = Math.max(0, quantity);
            this.weight = weight != null ? weight : BigDecimal.ZERO;
        }

        public Long getId() { return id; }
        public BigDecimal getUnitCost() { return unitCost; }
        public int getQuantity() { return quantity; }
        public BigDecimal getWeight() { return weight; }
        public BigDecimal getTotalValue() {
            return unitCost.multiply(BigDecimal.valueOf(quantity));
        }
        public BigDecimal getTotalWeight() {
            return weight.multiply(BigDecimal.valueOf(quantity));
        }
    }

    public static class Expense {
        private final BigDecimal amount;
        private final PurchaseExpense.AllocationMethod method;

        public Expense(BigDecimal amount, PurchaseExpense.AllocationMethod method) {
            this.amount = amount != null ? amount : BigDecimal.ZERO;
            this.method = method != null ? method : PurchaseExpense.AllocationMethod.VALUE;
        }

        public BigDecimal getAmount() { return amount; }
        public PurchaseExpense.AllocationMethod getMethod() { return method; }
    }

    /**
     * @return purchase-item id -> landed unit cost (unit cost + allocated
     *         expense per unit), rounded to 4 decimal places.
     */
    public static Map<Long, BigDecimal> allocateUnitCosts(List<Line> lines, List<Expense> expenses) {
        Map<Long, BigDecimal> allocatedByLine = new LinkedHashMap<>();
        for (Line line : lines) {
            allocatedByLine.put(line.getId(), BigDecimal.ZERO);
        }
        if (lines.isEmpty()) {
            return allocatedByLine;
        }

        for (Expense expense : expenses) {
            if (expense.getAmount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            Map<Long, BigDecimal> weights = weightsFor(lines, expense.getMethod());
            BigDecimal totalWeight = weights.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
                // Weight metadata is missing; fall back to value allocation.
                weights = weightsFor(lines, PurchaseExpense.AllocationMethod.VALUE);
                totalWeight = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal remaining = expense.getAmount();
            int index = 0;
            for (Line line : lines) {
                BigDecimal share;
                if (index == lines.size() - 1) {
                    // Assign the rounding remainder to the last line so the
                    // allocated total always equals the expense amount.
                    share = remaining;
                } else {
                    share = expense.getAmount()
                            .multiply(weights.get(line.getId()))
                            .divide(totalWeight, 2, RoundingMode.HALF_UP);
                    remaining = remaining.subtract(share);
                }
                allocatedByLine.merge(line.getId(), share, BigDecimal::add);
                index++;
            }
        }

        Map<Long, BigDecimal> unitCosts = new LinkedHashMap<>();
        for (Line line : lines) {
            BigDecimal allocated = allocatedByLine.getOrDefault(line.getId(), BigDecimal.ZERO);
            BigDecimal perUnit = line.getQuantity() > 0
                    ? allocated.divide(BigDecimal.valueOf(line.getQuantity()), COST_SCALE, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            unitCosts.put(line.getId(), line.getUnitCost().add(perUnit)
                    .setScale(COST_SCALE, RoundingMode.HALF_UP));
        }
        return unitCosts;
    }

    private static Map<Long, BigDecimal> weightsFor(List<Line> lines,
                                                    PurchaseExpense.AllocationMethod method) {
        Map<Long, BigDecimal> weights = new LinkedHashMap<>();
        for (Line line : lines) {
            BigDecimal weight;
            switch (method) {
                case QUANTITY:
                    weight = BigDecimal.valueOf(line.getQuantity());
                    break;
                case WEIGHT:
                    weight = line.getTotalWeight();
                    break;
                case VALUE:
                default:
                    weight = line.getTotalValue();
                    break;
            }
            weights.put(line.getId(), weight);
        }
        return weights;
    }
}
