package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable audit trail of everything that physically touches a drawer.
 * The signed amount is authoritative: expected cash is always the sum of the
 * movements, never a cached counter that can drift.
 */
@Entity
@Table(name = "cash_drawer_movements", indexes = {
    @Index(name = "idx_drawer_movement_session", columnList = "drawer_session_id"),
    @Index(name = "idx_drawer_movement_type", columnList = "movement_type"),
    @Index(name = "idx_drawer_movement_time", columnList = "occurred_at"),
    @Index(name = "idx_drawer_movement_shift", columnList = "employee_shift_id"),
    @Index(name = "idx_drawer_movement_reference", columnList = "reference_type, reference_id")
})
public class CashDrawerMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drawer_session_id", nullable = false)
    private CashDrawerSession drawerSession;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private DrawerMovementType movementType;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by")
    private User performedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_shift_id")
    private CashShift employeeShift;

    @Column(length = 1000)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public CashDrawerSession getDrawerSession() { return drawerSession; }
    public void setDrawerSession(CashDrawerSession drawerSession) { this.drawerSession = drawerSession; }

    public DrawerMovementType getMovementType() { return movementType; }
    public void setMovementType(DrawerMovementType movementType) { this.movementType = movementType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }

    public Long getReferenceId() { return referenceId; }
    public void setReferenceId(Long referenceId) { this.referenceId = referenceId; }

    public User getPerformedBy() { return performedBy; }
    public void setPerformedBy(User performedBy) { this.performedBy = performedBy; }

    public CashShift getEmployeeShift() { return employeeShift; }
    public void setEmployeeShift(CashShift employeeShift) { this.employeeShift = employeeShift; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
