package com.bms.service;

import com.bms.entity.Sale;
import com.bms.entity.SaleItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Allocates a sale-level discount across the sale's line items.
 *
 * <p>The POS stores list prices on {@link SaleItem} and keeps the discount on
 * the sale header. Revenue, profit and refunds must use the discounted line
 * value: otherwise reports overstate revenue/profit by the discount amount and
 * a return refunds more than the customer actually paid.
 *
 * <p>Allocation is proportional to each line's gross value. The rounding
 * remainder is assigned to the last line with a positive value, so the
 * allocated amounts always add up to the sale discount exactly.
 */
public final class SaleDiscountUtils {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private SaleDiscountUtils() {
    }

    /** Pre-discount, pre-tax value of a line. */
    public static BigDecimal grossLineValue(SaleItem item) {
        if (item == null) {
            return ZERO;
        }
        if (item.getTotalPrice() != null) {
            return item.getTotalPrice().setScale(2, RoundingMode.HALF_UP);
        }
        if (item.getUnitPrice() == null || item.getQuantity() == null) {
            return ZERO;
        }
        return item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Item id to its share of the sale discount. */
    public static Map<SaleItem, BigDecimal> allocateDiscounts(Sale sale) {
        Map<SaleItem, BigDecimal> allocations = new HashMap<>();
        if (sale == null) {
            return allocations;
        }
        List<SaleItem> items = sale.getItems();
        if (items == null || items.isEmpty()) {
            return allocations;
        }

        BigDecimal grossTotal = ZERO;
        for (SaleItem item : items) {
            grossTotal = grossTotal.add(grossLineValue(item));
        }

        BigDecimal discount = money(sale.getDiscountAmount());
        if (discount.signum() <= 0 || grossTotal.signum() <= 0) {
            for (SaleItem item : items) {
                allocations.put(item, ZERO);
            }
            return allocations;
        }
        if (discount.compareTo(grossTotal) > 0) {
            discount = grossTotal;
        }

        SaleItem remainderTarget = null;
        for (SaleItem item : items) {
            if (grossLineValue(item).signum() > 0) {
                remainderTarget = item;
            }
        }

        BigDecimal allocated = ZERO;
        for (SaleItem item : items) {
            BigDecimal gross = grossLineValue(item);
            BigDecimal share;
            if (gross.signum() <= 0) {
                share = ZERO;
            } else if (item == remainderTarget) {
                share = discount.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
            } else {
                share = discount.multiply(gross)
                        .divide(grossTotal, 2, RoundingMode.HALF_UP);
            }
            if (share.signum() < 0) {
                share = ZERO;
            }
            allocations.put(item, share);
            allocated = allocated.add(share);
        }
        return allocations;
    }

    public static BigDecimal discountForItem(Sale sale, SaleItem item) {
        if (sale == null || item == null) {
            return ZERO;
        }
        List<SaleItem> items = sale.getItems();
        if (items == null || items.isEmpty()) {
            return ZERO;
        }
        // A caller may hold an equivalent SaleItem instance from a different
        // repository lookup; match by id first, then fall back to identity.
        for (SaleItem line : items) {
            boolean sameLine = line == item
                    || (line.getId() != null && line.getId().equals(item.getId()));
            if (sameLine) {
                return allocateDiscounts(sale).getOrDefault(line, ZERO);
            }
        }
        return ZERO;
    }

    /** Revenue of the non-refunded units of one line, net of its discount share. */
    public static BigDecimal netItemRevenue(SaleItem item, BigDecimal allocatedDiscount, int quantitySold) {
        if (item == null || quantitySold <= 0) {
            return ZERO;
        }
        int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
        if (quantity <= 0) {
            return ZERO;
        }
        BigDecimal discounted = grossLineValue(item).subtract(money(allocatedDiscount));
        if (discounted.signum() < 0) {
            discounted = ZERO;
        }
        return discounted.multiply(BigDecimal.valueOf(quantitySold))
                .divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal netItemRevenue(Sale sale, SaleItem item, int quantitySold) {
        return netItemRevenue(item, discountForItem(sale, item), quantitySold);
    }

    /** Net revenue of all non-refunded units of a sale (discount applied). */
    public static BigDecimal netSaleRevenue(Sale sale) {
        if (sale == null) {
            return ZERO;
        }
        List<SaleItem> items = sale.getItems();
        if (items == null || items.isEmpty()) {
            return ZERO;
        }

        Map<SaleItem, BigDecimal> allocations = allocateDiscounts(sale);
        BigDecimal gross = ZERO;
        BigDecimal refundedRevenue = ZERO;
        for (SaleItem item : items) {
            gross = gross.add(grossLineValue(item));
            int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
            int refunded = item.getQuantityRefunded() != null ? item.getQuantityRefunded() : 0;
            if (quantity > 0 && refunded > 0) {
                int refundedQty = Math.min(refunded, quantity);
                refundedRevenue = refundedRevenue.add(
                        netItemRevenue(item, allocations.getOrDefault(item, ZERO), refundedQty));
            }
        }
        BigDecimal discount = money(sale.getDiscountAmount());
        if (discount.compareTo(gross) > 0) {
            discount = gross;
        }
        BigDecimal net = gross.subtract(discount).subtract(refundedRevenue);
        return net.signum() > 0 ? net : ZERO;
    }

    /**
     * Discount-aware revenue for a line when only aggregate values are known
     * (product summaries, heat maps). {@code lineTotal} is the full pre-discount
     * line value for {@code quantity} units; the result covers
     * {@code quantitySold} units.
     */
    public static BigDecimal netLineRevenue(BigDecimal lineTotal, BigDecimal saleSubtotal,
                                            BigDecimal saleDiscount, int quantity, int quantitySold) {
        if (lineTotal == null || quantity <= 0 || quantitySold <= 0) {
            return ZERO;
        }
        BigDecimal subtotal = money(saleSubtotal);
        BigDecimal discount = money(saleDiscount);
        BigDecimal itemDiscount = ZERO;
        if (subtotal.signum() > 0 && discount.signum() > 0) {
            itemDiscount = discount.multiply(lineTotal)
                    .divide(subtotal, 4, RoundingMode.HALF_UP);
        }
        BigDecimal discounted = lineTotal.subtract(itemDiscount);
        if (discounted.signum() < 0) {
            discounted = ZERO;
        }
        return discounted.multiply(BigDecimal.valueOf(quantitySold))
                .divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
    }

    /** Per-unit refund derived from the stored line values, net of discount. */
    public static BigDecimal unitRefundAmount(BigDecimal lineTotal, BigDecimal lineTax,
                                              BigDecimal allocatedDiscount, int quantity) {
        if (quantity <= 0) {
            return ZERO;
        }
        BigDecimal net = money(lineTotal).subtract(money(allocatedDiscount)).add(money(lineTax));
        if (net.signum() < 0) {
            net = ZERO;
        }
        return net.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value != null ? value : ZERO;
    }
}
