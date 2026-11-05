package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One open/close cycle of a physical cash drawer. Employee shifts attach to a
 * session; the expected cash and variance belong to this drawer, never to an
 * individual employee.
 */
@Entity
@Table(name = "cash_drawer_sessions", indexes = {
    @Index(name = "idx_drawer_session_drawer", columnList = "drawer_id"),
    @Index(name = "idx_drawer_session_status", columnList = "status"),
    @Index(name = "idx_drawer_session_opened", columnList = "opened_at"),
    @Index(name = "idx_drawer_session_drawer_status", columnList = "drawer_id, status")
})
public class CashDrawerSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drawer_id", nullable = false)
    private CashDrawer drawer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opened_by")
    private User openedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    private User closedBy;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DrawerSessionStatus status = DrawerSessionStatus.OPEN;

    @Column(name = "opening_float", nullable = false, precision = 12, scale = 2,
            columnDefinition = "numeric(12,2) not null default 0")
    private BigDecimal openingFloat = BigDecimal.ZERO;

    @Column(name = "expected_cash", precision = 12, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "counted_cash", precision = 12, scale = 2)
    private BigDecimal countedCash;

    @Column(name = "variance_amount", precision = 12, scale = 2)
    private BigDecimal varianceAmount;

    @Column(name = "closing_note", length = 1000)
    private String closingNote;

    @Column(name = "blind_close", nullable = false,
            columnDefinition = "boolean not null default 1")
    private Boolean blindClose = true;

    @Column(name = "force_closed", nullable = false,
            columnDefinition = "boolean not null default 0")
    private Boolean forceClosed = false;

    @Column(name = "force_close_reason", length = 1000)
    private String forceCloseReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (openedAt == null) {
            openedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = DrawerSessionStatus.OPEN;
        }
        if (openingFloat == null) {
            openingFloat = BigDecimal.ZERO;
        }
        if (blindClose == null) {
            blindClose = true;
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

    public CashDrawer getDrawer() { return drawer; }
    public void setDrawer(CashDrawer drawer) { this.drawer = drawer; }

    public User getOpenedBy() { return openedBy; }
    public void setOpenedBy(User openedBy) { this.openedBy = openedBy; }

    public User getClosedBy() { return closedBy; }
    public void setClosedBy(User closedBy) { this.closedBy = closedBy; }

    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }

    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public DrawerSessionStatus getStatus() { return status; }
    public void setStatus(DrawerSessionStatus status) { this.status = status; }

    public BigDecimal getOpeningFloat() { return openingFloat; }
    public void setOpeningFloat(BigDecimal openingFloat) { this.openingFloat = openingFloat; }

    public BigDecimal getExpectedCash() { return expectedCash; }
    public void setExpectedCash(BigDecimal expectedCash) { this.expectedCash = expectedCash; }

    public BigDecimal getCountedCash() { return countedCash; }
    public void setCountedCash(BigDecimal countedCash) { this.countedCash = countedCash; }

    public BigDecimal getVarianceAmount() { return varianceAmount; }
    public void setVarianceAmount(BigDecimal varianceAmount) { this.varianceAmount = varianceAmount; }

    public String getClosingNote() { return closingNote; }
    public void setClosingNote(String closingNote) { this.closingNote = closingNote; }

    public Boolean getBlindClose() { return blindClose; }
    public void setBlindClose(Boolean blindClose) { this.blindClose = blindClose; }

    public Boolean getForceClosed() { return forceClosed; }
    public void setForceClosed(Boolean forceClosed) { this.forceClosed = forceClosed; }

    public String getForceCloseReason() { return forceCloseReason; }
    public void setForceCloseReason(String forceCloseReason) { this.forceCloseReason = forceCloseReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
