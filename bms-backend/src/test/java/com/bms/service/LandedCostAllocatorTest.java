package com.bms.service;

import com.bms.entity.PurchaseExpense;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LandedCostAllocatorTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static LandedCostAllocator.Line line(long id, String unitCost, int quantity, String weight) {
        return new LandedCostAllocator.Line(id, bd(unitCost), quantity, bd(weight));
    }

    private static LandedCostAllocator.Expense expense(String amount,
                                                       PurchaseExpense.AllocationMethod method) {
        return new LandedCostAllocator.Expense(bd(amount), method);
    }

    @Test
    void allocatesByValue() {
        List<LandedCostAllocator.Line> lines = List.of(
                line(1L, "10.00", 1, "1"),
                line(2L, "20.00", 1, "1"));

        Map<Long, BigDecimal> costs = LandedCostAllocator.allocateUnitCosts(lines,
                List.of(expense("30.00", PurchaseExpense.AllocationMethod.VALUE)));

        // 30 split 10/30 and 20/30 => 10.00 and 20.00 per unit.
        assertEquals(bd("20.0000"), costs.get(1L));
        assertEquals(bd("40.0000"), costs.get(2L));
    }

    @Test
    void allocatesByQuantity() {
        List<LandedCostAllocator.Line> lines = List.of(
                line(1L, "5.00", 2, "1"),
                line(2L, "5.00", 3, "1"));

        Map<Long, BigDecimal> costs = LandedCostAllocator.allocateUnitCosts(lines,
                List.of(expense("50.00", PurchaseExpense.AllocationMethod.QUANTITY)));

        // 50 split 2/5 and 3/5 => 20 and 30; per unit 10 each.
        assertEquals(bd("15.0000"), costs.get(1L));
        assertEquals(bd("15.0000"), costs.get(2L));
    }

    @Test
    void allocatesByWeight() {
        List<LandedCostAllocator.Line> lines = List.of(
                line(1L, "0.00", 1, "1"),
                line(2L, "0.00", 1, "3"));

        Map<Long, BigDecimal> costs = LandedCostAllocator.allocateUnitCosts(lines,
                List.of(expense("40.00", PurchaseExpense.AllocationMethod.WEIGHT)));

        assertEquals(bd("10.0000"), costs.get(1L));
        assertEquals(bd("30.0000"), costs.get(2L));
    }

    @Test
    void fallsBackToValueWhenWeightMetadataIsMissing() {
        List<LandedCostAllocator.Line> lines = List.of(
                line(1L, "10.00", 1, "0"),
                line(2L, "30.00", 1, "0"));

        Map<Long, BigDecimal> costs = LandedCostAllocator.allocateUnitCosts(lines,
                List.of(expense("40.00", PurchaseExpense.AllocationMethod.WEIGHT)));

        // 40 split 10/40 and 30/40 => 10 and 30 added to the base costs.
        assertEquals(bd("20.0000"), costs.get(1L));
        assertEquals(bd("60.0000"), costs.get(2L));
    }

    @Test
    void roundingRemainderGoesToLastLineSoAllocatedTotalIsExact() {
        List<LandedCostAllocator.Line> lines = List.of(
                line(1L, "0.00", 1, "1"),
                line(2L, "0.00", 1, "1"),
                line(3L, "0.00", 1, "1"));

        Map<Long, BigDecimal> costs = LandedCostAllocator.allocateUnitCosts(lines,
                List.of(expense("10.00", PurchaseExpense.AllocationMethod.QUANTITY)));

        assertEquals(bd("3.3300"), costs.get(1L));
        assertEquals(bd("3.3300"), costs.get(2L));
        assertEquals(bd("3.3400"), costs.get(3L));

        BigDecimal allocatedTotal = costs.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, allocatedTotal.compareTo(bd("10.00")));
    }

    @Test
    void emptyLinesReturnEmptyMap() {
        Map<Long, BigDecimal> costs = LandedCostAllocator.allocateUnitCosts(List.of(),
                List.of(expense("10.00", PurchaseExpense.AllocationMethod.VALUE)));

        assertEquals(Map.of(), costs);
    }
}
