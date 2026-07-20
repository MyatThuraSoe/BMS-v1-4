package com.bms.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.List;

public class SaleCreateRequest {
    @NotEmpty(message = "Cart items are required")
    private List<SaleItemRequest> items;

    private Long customerId;

    @NotNull(message = "Amount paid is required")
    @Min(value = 0, message = "Amount paid must be at least 0")
    private BigDecimal amountPaid;

    /**
     * CASH or CREDIT
     */
    private String paymentMethod;

    private String notes;

    /**
     * Discount type:
     * - PERCENTAGE: discountValue is percentage (0..100)
     * - FIXED: discountValue is fixed amount
     * - null: no discount
     */
    private String discountType;

    /**
     * Meaning depends on discountType:
     * - PERCENTAGE: 10 means 10%
     * - FIXED: 2.00 means $2 off
     */
    private BigDecimal discountValue;

    /**
     * Optional human-readable reason (e.g., "10% off — loyalty").
     */
    private String discountReason;

    public List<SaleItemRequest> getItems() { return items; }
    public void setItems(List<SaleItemRequest> items) { this.items = items; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public BigDecimal getAmountPaid() { return amountPaid; }
    public void setAmountPaid(BigDecimal amountPaid) { this.amountPaid = amountPaid; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getDiscountType() { return discountType; }
    public void setDiscountType(String discountType) { this.discountType = discountType; }

    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }

    public String getDiscountReason() { return discountReason; }
    public void setDiscountReason(String discountReason) { this.discountReason = discountReason; }

    public static class SaleItemRequest {
        @NotNull(message = "Product ID is required")
        private Long productId;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        private Integer quantity;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}
