package com.bms.dto.request;

import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Opens an employee work shift.
 *
 * <p>{@code openingAmount} is kept as the legacy name used by older clients.
 * New clients send {@code shiftType} plus, for cash drawer shifts,
 * {@code drawerId}/{@code openingFloat}. Non-cash shifts do not require any
 * float at all.
 */
public class OpenShiftRequest {

    /** CASH_DRAWER (default for backwards compatibility), NON_CASH, SUPERVISOR. */
    private String shiftType;

    private Long drawerId;

    @PositiveOrZero(message = "Opening float must be zero or positive")
    private BigDecimal openingFloat;

    /** Legacy alias for openingFloat. */
    @PositiveOrZero(message = "Opening amount must be zero or positive")
    private BigDecimal openingAmount;

    /** Join the currently open drawer session instead of opening a new one. */
    private Boolean joinExisting;

    /** Manager credentials when shared-drawer joining requires approval. */
    private String approverUsername;
    private String approverPassword;

    private String notes;

    public String getShiftType() { return shiftType; }
    public void setShiftType(String shiftType) { this.shiftType = shiftType; }

    public Long getDrawerId() { return drawerId; }
    public void setDrawerId(Long drawerId) { this.drawerId = drawerId; }

    public BigDecimal getOpeningFloat() { return openingFloat; }
    public void setOpeningFloat(BigDecimal openingFloat) { this.openingFloat = openingFloat; }

    public BigDecimal getOpeningAmount() { return openingAmount; }
    public void setOpeningAmount(BigDecimal openingAmount) { this.openingAmount = openingAmount; }

    public Boolean getJoinExisting() { return joinExisting; }
    public void setJoinExisting(Boolean joinExisting) { this.joinExisting = joinExisting; }

    public String getApproverUsername() { return approverUsername; }
    public void setApproverUsername(String approverUsername) { this.approverUsername = approverUsername; }

    public String getApproverPassword() { return approverPassword; }
    public void setApproverPassword(String approverPassword) { this.approverPassword = approverPassword; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    /** The float supplied by either the new or legacy field. */
    public BigDecimal resolvedOpeningFloat() {
        return openingFloat != null ? openingFloat : openingAmount;
    }
}
