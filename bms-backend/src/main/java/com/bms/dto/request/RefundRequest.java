package com.bms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class RefundRequest {
    @NotBlank(message = "Refund reason is required")
    private String reason;

    @Valid
    @NotEmpty(message = "At least one refund item is required")
    private List<RefundItemRequest> items;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public List<RefundItemRequest> getItems() { return items; }
    public void setItems(List<RefundItemRequest> items) { this.items = items; }

    public static class RefundItemRequest {
        @NotNull(message = "Sale item ID is required")
        private Long saleItemId;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        private Integer quantity;

        public Long getSaleItemId() { return saleItemId; }
        public void setSaleItemId(Long saleItemId) { this.saleItemId = saleItemId; }

        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}
