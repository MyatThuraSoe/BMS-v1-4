package com.bms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cash_shifts", indexes = {
    @Index(name = "idx_cash_shift_cashier", columnList = "cashier_id"),
    @Index(name = "idx_cash_shift_status", columnList = "status"),
    @Index(name = "idx_cash_shift_opening_time", columnList = "opening_time")
})
public class CashShift {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cashier_id", nullable = false)
    private Long cashierId;

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

    @Column(name = "status", nullable = false, length = 20)
    private String status = "OPEN";

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (openingTime == null) {
            openingTime = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCashierId() { return cashierId; }
    public void setCashierId(Long cashierId) { this.cashierId = cashierId; }

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
}
