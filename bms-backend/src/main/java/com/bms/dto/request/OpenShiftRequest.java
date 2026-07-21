package com.bms.dto.request;

import java.math.BigDecimal;

public class OpenShiftRequest {
    private BigDecimal openingAmount;

    public BigDecimal getOpeningAmount() { return openingAmount; }
    public void setOpeningAmount(BigDecimal openingAmount) { this.openingAmount = openingAmount; }
}
