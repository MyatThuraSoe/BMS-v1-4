package com.bms.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

public class SaleCreateRequest {
    @NotEmpty(message = "Cart items are required")
    private List<SaleItemRequest> items;

    private Long customerId;

    @NotNull(message = "Amount paid is required")
    @Positive(message = "Amount paid must be positive")
    private BigDecimal amountPaid;

    private String notes;

    public List<SaleItemRequest> getItems() { return items; }
    public void setItems(List<SaleItemRequest> items) { this.items = items; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public BigDecimal getAmountPaid() { return amountPaid; }
    public void setAmountPaid(BigDecimal amountPaid) { this.amountPaid = amountPaid; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public static class SaleItemRequest {
        @NotNull(message = "Product ID is required")
        private Long productId;

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be positive")
        private Integer quantity;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}
