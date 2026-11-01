package com.bms.service;

import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SaleDiscountUtilsTest {

    @Test
    void allocatesDiscountProportionallyToLineValue() {
        Sale sale = saleWithDiscount("200.00");
        SaleItem first = item(sale, "3000.00", 1);
        SaleItem second = item(sale, "1000.00", 1);

        Map<SaleItem, BigDecimal> allocations = SaleDiscountUtils.allocateDiscounts(sale);

        assertEquals(new BigDecimal("150.00"), allocations.get(first));
        assertEquals(new BigDecimal("50.00"), allocations.get(second));
    }

    @Test
    void roundingRemainderGoesToLastLineSoTotalIsExact() {
        Sale sale = saleWithDiscount("100.00");
        SaleItem first = item(sale, "100.00", 1);
        SaleItem second = item(sale, "100.00", 1);
        SaleItem third = item(sale, "100.00", 1);

        Map<SaleItem, BigDecimal> allocations = SaleDiscountUtils.allocateDiscounts(sale);

        assertEquals(new BigDecimal("33.33"), allocations.get(first));
        assertEquals(new BigDecimal("33.33"), allocations.get(second));
        assertEquals(new BigDecimal("33.34"), allocations.get(third));
        assertEquals(new BigDecimal("100.00"),
                allocations.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void partiallyRefundedLineUsesItsDiscountShare() {
        SaleItem line = new SaleItem();
        line.setQuantity(3);
        line.setUnitPrice(new BigDecimal("1000.00"));
        line.setTotalPrice(new BigDecimal("3000.00"));

        BigDecimal revenue = SaleDiscountUtils.netItemRevenue(line, new BigDecimal("150.00"), 2);

        assertEquals(new BigDecimal("1900.00"), revenue);
    }

    @Test
    void saleRevenueNetsOutRefundedDiscountedLine() {
        Sale sale = saleWithDiscount("200.00");
        SaleItem refunded = item(sale, "3000.00", 1);
        refunded.setQuantityRefunded(1);
        SaleItem kept = item(sale, "3000.00", 1);

        BigDecimal revenue = SaleDiscountUtils.netSaleRevenue(sale);

        assertEquals(new BigDecimal("2900.00"), revenue);
        assertEquals(new BigDecimal("100.00"), SaleDiscountUtils.discountForItem(sale, kept));
    }

    @Test
    void unitRefundSubtractsDiscountAndAddsTax() {
        BigDecimal refund = SaleDiscountUtils.unitRefundAmount(
                new BigDecimal("3000.00"),
                BigDecimal.ZERO,
                new BigDecimal("150.00"),
                3
        );

        assertEquals(0, refund.compareTo(new BigDecimal("950.00")));
    }

    @Test
    void unitRefundIncludesTaxAfterDiscount() {
        BigDecimal refund = SaleDiscountUtils.unitRefundAmount(
                new BigDecimal("1000.00"),
                new BigDecimal("50.00"),
                new BigDecimal("100.00"),
                2
        );

        assertEquals(0, refund.compareTo(new BigDecimal("475.00")));
    }

    @Test
    void discountCannotExceedGrossValue() {
        Sale sale = saleWithDiscount("5000.00");
        SaleItem line = item(sale, "3000.00", 1);

        assertEquals(new BigDecimal("3000.00"), SaleDiscountUtils.discountForItem(sale, line));
        assertEquals(BigDecimal.ZERO, SaleDiscountUtils.netSaleRevenue(sale));
    }

    @Test
    void noDiscountLeavesRevenueUntouched() {
        Sale sale = saleWithDiscount(null);
        SaleItem line = item(sale, "2000.00", 2);
        line.setQuantityRefunded(1);

        assertEquals(new BigDecimal("1000.00"), SaleDiscountUtils.netSaleRevenue(sale));
    }

    @Test
    void aggregateLineRevenueAppliesSaleDiscount() {
        BigDecimal full = SaleDiscountUtils.netLineRevenue(
                new BigDecimal("3000.00"),
                new BigDecimal("4000.00"),
                new BigDecimal("200.00"),
                3,
                3
        );
        BigDecimal partial = SaleDiscountUtils.netLineRevenue(
                new BigDecimal("3000.00"),
                new BigDecimal("4000.00"),
                new BigDecimal("200.00"),
                3,
                2
        );

        assertEquals(new BigDecimal("2850.00"), full);
        assertEquals(new BigDecimal("1900.00"), partial);
    }

    private Sale saleWithDiscount(String discount) {
        Sale sale = new Sale();
        sale.setDiscountAmount(discount != null ? new BigDecimal(discount) : null);
        return sale;
    }

    private SaleItem item(Sale sale, String totalPrice, int quantity) {
        SaleItem item = new SaleItem();
        item.setSale(sale);
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal(totalPrice)
                .divide(BigDecimal.valueOf(quantity), 2, java.math.RoundingMode.HALF_UP));
        item.setTotalPrice(new BigDecimal(totalPrice));
        item.setQuantityRefunded(0);
        sale.getItems().add(item);
        return item;
    }
}
