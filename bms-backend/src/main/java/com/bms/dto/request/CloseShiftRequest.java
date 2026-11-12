package com.bms.dto.request;

import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Closes an employee shift. Cash drawer shifts may include a blind count and
 * ask for the drawer to be closed too; non-cash shifts ignore all cash fields.
 */
public class CloseShiftRequest {

    /** Legacy name for counted cash. */
    @PositiveOrZero(message = "Closing amount must be zero or positive")
    private BigDecimal closingAmount;

    @PositiveOrZero(message = "Counted cash must be zero or positive")
    private BigDecimal countedCash;

    /** Legacy notes field. */
    private String notes;

    private String closingNote;

    /** Close the physical drawer as part of this shift close. */
    private Boolean closeDrawer;

    /** Manager/admin force-close, required when other employees share the drawer. */
    private Boolean forceClose;

    private String forceReason;

    public BigDecimal getClosingAmount() { return closingAmount; }
    public void setClosingAmount(BigDecimal closingAmount) { this.closingAmount = closingAmount; }

    public BigDecimal getCountedCash() { return countedCash; }
    public void setCountedCash(BigDecimal countedCash) { this.countedCash = countedCash; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getClosingNote() { return closingNote; }
    public void setClosingNote(String closingNote) { this.closingNote = closingNote; }

    public Boolean getCloseDrawer() { return closeDrawer; }
    public void setCloseDrawer(Boolean closeDrawer) { this.closeDrawer = closeDrawer; }

    public Boolean getForceClose() { return forceClose; }
    public void setForceClose(Boolean forceClose) { this.forceClose = forceClose; }

    public String getForceReason() { return forceReason; }
    public void setForceReason(String forceReason) { this.forceReason = forceReason; }

    public BigDecimal resolvedCountedCash() {
        return countedCash != null ? countedCash : closingAmount;
    }

    public String resolvedNote() {
        return closingNote != null && !closingNote.isBlank() ? closingNote : notes;
    }
}
