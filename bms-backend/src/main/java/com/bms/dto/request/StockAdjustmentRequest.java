package com.bms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class StockAdjustmentRequest {
    @NotNull(message = "Product ID is required")
    private Long productId;

    @NotNull(message = "Quantity adjustment is required")
    private Integer quantityChange;

    /**
     * SPOILAGE_EXPIRY, THEFT_SHRINKAGE, DAMAGED_IN_STORE,
     * INTERNAL_CONSUMPTION or COUNT_CORRECTION.
     */
    @NotBlank(message = "Reason code is required")
    private String reasonCode;

    /** Free-text note shown on the movement ledger. */
    private String reason;

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Integer getQuantityChange() { return quantityChange; }
    public void setQuantityChange(Integer quantityChange) { this.quantityChange = quantityChange; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
