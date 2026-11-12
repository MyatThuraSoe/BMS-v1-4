package com.bms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cash_shifts", indexes = {
    @Index(name = "idx_cash_shift_cashier", columnList = "cashier_id"),
    @Index(name = "idx_cash_shift_status", columnList = "status"),
    @Index(name = "idx_cash_shift_cashier_status", columnList = "cashier_id, status"), // Added: Optimizes "find open shift by cashier"
    @Index(name = "idx_cash_shift_opening_time", columnList = "opening_time")          // Added: Optimizes date-range history queries
})
public class CashShift {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cashier_id", nullable = false)
    private Long cashierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_type", nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'CASH_DRAWER'")
    private ShiftType shiftType = ShiftType.CASH_DRAWER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "drawer_session_id")
    private CashDrawerSession drawerSession;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "force_closed", nullable = false,
            columnDefinition = "boolean not null default 0")
    private Boolean forceClosed = false;

    @Column(name = "force_close_reason", length = 1000)
    private String forceCloseReason;

    @Column(name = "opening_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal openingAmount;

    @Column(name = "opening_time", nullable = false)
    private LocalDateTime openingTime;

    @Column(name = "closing_amount", precision = 10, scale = 2)
    private BigDecimal closingAmount;

    @Column(name = "closing_time")
    private LocalDateTime closingTime;

    @Column(name = "expected_amount", precision = 10, scale = 2)
    private BigDecimal expectedAmount;

    @Column(name = "variance", precision = 10, scale = 2)
    private BigDecimal variance;

    @Column(nullable = false, length = 20)
    private String status = "OPEN";

    @Column(length = 1000)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (shiftType == null) {
            shiftType = ShiftType.CASH_DRAWER;
        }
        if (forceClosed == null) {
            forceClosed = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCashierId() { return cashierId; }
    public void setCashierId(Long cashierId) { this.cashierId = cashierId; }

    public ShiftType getShiftType() {
        return shiftType != null ? shiftType : ShiftType.CASH_DRAWER;
    }
    public void setShiftType(ShiftType shiftType) { this.shiftType = shiftType; }

    public CashDrawerSession getDrawerSession() { return drawerSession; }
    public void setDrawerSession(CashDrawerSession drawerSession) { this.drawerSession = drawerSession; }

    public Long getClosedBy() { return closedBy; }
    public void setClosedBy(Long closedBy) { this.closedBy = closedBy; }

    public Boolean getForceClosed() { return forceClosed != null ? forceClosed : false; }
    public void setForceClosed(Boolean forceClosed) { this.forceClosed = forceClosed; }

    public String getForceCloseReason() { return forceCloseReason; }
    public void setForceCloseReason(String forceCloseReason) { this.forceCloseReason = forceCloseReason; }

    public BigDecimal getOpeningAmount() { return openingAmount; }
    public void setOpeningAmount(BigDecimal openingAmount) { this.openingAmount = openingAmount; }

    public LocalDateTime getOpeningTime() { return openingTime; }
    public void setOpeningTime(LocalDateTime openingTime) { this.openingTime = openingTime; }

    public BigDecimal getClosingAmount() { return closingAmount; }
    public void setClosingAmount(BigDecimal closingAmount) { this.closingAmount = closingAmount; }

    public LocalDateTime getClosingTime() { return closingTime; }
    public void setClosingTime(LocalDateTime closingTime) { this.closingTime = closingTime; }

    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(BigDecimal expectedAmount) { this.expectedAmount = expectedAmount; }

    public BigDecimal getVariance() { return variance; }
    public void setVariance(BigDecimal variance) { this.variance = variance; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}