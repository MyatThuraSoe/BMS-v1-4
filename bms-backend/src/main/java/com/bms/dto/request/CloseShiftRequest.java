package com.bms.dto.request;

import java.math.BigDecimal;

public class CloseShiftRequest {
    private BigDecimal closingAmount;
    private String notes;

    public BigDecimal getClosingAmount() { return closingAmount; }
    public void setClosingAmount(BigDecimal closingAmount) { this.closingAmount = closingAmount; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
