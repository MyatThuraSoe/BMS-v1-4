package com.bms.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * C4/D4: explicit, reasoned cost revaluation. ADMIN only. The expectedVersion
 * must match the product's current optimistic-lock version so a stale form
 * cannot overwrite a cost that a receipt has already moved.
 */
public class CostAdjustmentRequest {

    @NotNull(message = "New cost is required")
    @DecimalMin(value = "0.0", message = "Cost cannot be negative")
    private BigDecimal newCost;

    @NotBlank(message = "A reason is required for cost adjustments")
    @Size(min = 10, max = 500, message = "Reason must be at least 10 characters")
    private String reason;

    @NotNull(message = "expectedVersion is required")
    private Long expectedVersion;

    public BigDecimal getNewCost() { return newCost; }
    public void setNewCost(BigDecimal newCost) { this.newCost = newCost; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }
}