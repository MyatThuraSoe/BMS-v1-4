package com.bms.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public class OpenShiftRequest {
    @NotNull(message = "Opening amount is required")
    @PositiveOrZero(message = "Opening amount must be zero or positive")
    private BigDecimal openingAmount;

    public BigDecimal getOpeningAmount() { return openingAmount; }
    public void setOpeningAmount(BigDecimal openingAmount) { this.openingAmount = openingAmount; }
}
