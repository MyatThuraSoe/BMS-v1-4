package com.bms.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public class CloseShiftRequest {
    @NotNull(message = "Closing amount is required")
    @PositiveOrZero(message = "Closing amount must be zero or positive")
    private BigDecimal closingAmount;

    private String notes;

    public BigDecimal getClosingAmount() { return closingAmount; }
    public void setClosingAmount(BigDecimal closingAmount) { this.closingAmount = closingAmount; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
