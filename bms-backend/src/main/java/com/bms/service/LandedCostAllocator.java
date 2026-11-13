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
        List<Map<Long, BigDecimal>> perExpense = allocateExpenseAmounts(lines, expenses);
        Map<Long, BigDecimal> allocatedByLine = new LinkedHashMap<>();
        for (Line line : lines) {
            allocatedByLine.put(line.getId(), BigDecimal.ZERO);
        }
        for (Map<Long, BigDecimal> shares : perExpense) {
            for (Line line : lines) {
                allocatedByLine.merge(line.getId(),
                        shares.getOrDefault(line.getId(), BigDecimal.ZERO), BigDecimal::add);
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

    /**
     * Raw allocated amount of each expense per line (invoice currency, 2 dp),
     * one map per expense in input order. The rounding remainder is assigned to
     * the last line carrying positive weight so the allocated total always
     * equals the expense amount. Receipt-time capitalisation builds on this.
     */
    public static List<Map<Long, BigDecimal>> allocateExpenseAmounts(List<Line> lines, List<Expense> expenses) {
        List<Map<Long, BigDecimal>> result = new ArrayList<>();
        for (Expense expense : expenses) {
            if (lines.isEmpty() || expense.getAmount().compareTo(BigDecimal.ZERO) == 0) {
                result.add(zeroShares(lines));
                continue;
            }
            Map<Long, BigDecimal> weights = weightsFor(lines, expense.getMethod());
            if (totalWeight(weights).compareTo(BigDecimal.ZERO) <= 0) {
                // Weight metadata is missing; fall back to value allocation.
                weights = weightsFor(lines, PurchaseExpense.AllocationMethod.VALUE);
            }
            result.add(splitByWeights(lines, weights, expense.getAmount()));
        }
        return result;
    }

    /**
     * Allocates a header amount (discount or non-recoverable tax) across lines
     * in proportion to line value, remainder on a real (positive-value) line.
     */
    public static Map<Long, BigDecimal> allocateByValue(List<Line> lines, BigDecimal amount) {
        if (lines.isEmpty() || amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return zeroShares(lines);
        }
        return splitByWeights(lines, weightsFor(lines, PurchaseExpense.AllocationMethod.VALUE), amount);
    }

    private static Map<Long, BigDecimal> zeroShares(List<Line> lines) {
        Map<Long, BigDecimal> shares = new LinkedHashMap<>();
        for (Line line : lines) {
            shares.put(line.getId(), BigDecimal.ZERO);
        }
        return shares;
    }

    private static BigDecimal totalWeight(Map<Long, BigDecimal> weights) {
        return weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<Long, BigDecimal> splitByWeights(List<Line> lines,
                                                        Map<Long, BigDecimal> weights,
                                                        BigDecimal amount) {
        Map<Long, BigDecimal> shares = zeroShares(lines);
        BigDecimal totalWeight = totalWeight(weights);
        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
            // No positive weight anywhere: park the whole amount on the last line.
            shares.put(lines.get(lines.size() - 1).getId(), amount.setScale(2, RoundingMode.HALF_UP));
            return shares;
        }
        int remainderIndex = lines.size() - 1;
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (weights.getOrDefault(lines.get(i).getId(), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0) {
                remainderIndex = i;
                break;
            }
        }
        BigDecimal remaining = amount;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            BigDecimal share;
            if (i == remainderIndex) {
                share = remaining.setScale(2, RoundingMode.HALF_UP);
            } else {
                share = amount.multiply(weights.get(line.getId()))
                        .divide(totalWeight, 2, RoundingMode.HALF_UP);
                remaining = remaining.subtract(share);
            }
            shares.put(line.getId(), share);
        }
        return shares;
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
