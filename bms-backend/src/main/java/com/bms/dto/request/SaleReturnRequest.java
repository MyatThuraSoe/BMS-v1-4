package com.bms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class SaleReturnRequest {
    @NotBlank(message = "Return reason is required")
    private String reason;

    @Valid
    @NotEmpty(message = "At least one return item is required")
    private List<SaleReturnItemRequest> items;

    /** CASH, MOBILE_WALLET, CARD, BANK_TRANSFER, etc. Defaults from the original sale. */
    private String refundMethod;

    private String externalReference;

    public String getRefundMethod() { return refundMethod; }
    public void setRefundMethod(String refundMethod) { this.refundMethod = refundMethod; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public List<SaleReturnItemRequest> getItems() { return items; }
    public void setItems(List<SaleReturnItemRequest> items) { this.items = items; }

    public static class SaleReturnItemRequest {
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
