package com.bms.dto.request;

import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public class OpenShiftRequest {
    @Positive(message = "Opening amount must be positive")
    private BigDecimal openingAmount;

    public BigDecimal getOpeningAmount() { return openingAmount; }
    public void setOpeningAmount(BigDecimal openingAmount) { this.openingAmount = openingAmount; }
}
