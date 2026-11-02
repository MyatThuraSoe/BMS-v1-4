package com.bms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public class ReturnToVendorRequest {

    @NotEmpty(message = "At least one return line is required")
    @Valid
    private List<ReturnItem> items;

    private String reason;

    public List<ReturnItem> getItems() { return items; }
    public void setItems(List<ReturnItem> items) { this.items = items; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public static class ReturnItem {
        @NotNull(message = "Purchase item id is required")
        private Long purchaseItemId;

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be positive")
        private Integer quantity;

        private String reasonCode;

        public Long getPurchaseItemId() { return purchaseItemId; }
        public void setPurchaseItemId(Long purchaseItemId) { this.purchaseItemId = purchaseItemId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    }
}
