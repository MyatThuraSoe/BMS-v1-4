package com.bms.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** Blind close of a physical drawer session. */
public class CloseDrawerSessionRequest {

    @NotNull(message = "Counted cash is required")
    @PositiveOrZero(message = "Counted cash must be zero or positive")
    private BigDecimal countedCash;

    private String closingNote;

    /** Manager/admin force close when another employee is still attached. */
    private Boolean forceClose;

    private String forceReason;

    public BigDecimal getCountedCash() { return countedCash; }
    public void setCountedCash(BigDecimal countedCash) { this.countedCash = countedCash; }

    public String getClosingNote() { return closingNote; }
    public void setClosingNote(String closingNote) { this.closingNote = closingNote; }

    public Boolean getForceClose() { return forceClose; }
    public void setForceClose(Boolean forceClose) { this.forceClose = forceClose; }

    public String getForceReason() { return forceReason; }
    public void setForceReason(String forceReason) { this.forceReason = forceReason; }
}
