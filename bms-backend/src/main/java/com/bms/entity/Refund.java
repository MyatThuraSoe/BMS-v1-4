package com.bms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "refunds", indexes = {
    @Index(name = "idx_refund_sale", columnList = "sale_id"),
    @Index(name = "idx_refund_user", columnList = "refunded_by"),
    @Index(name = "idx_refund_date", columnList = "refund_date")
})
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refunded_by", nullable = false)
    private User refundedBy;

    @Column(name = "refund_date", nullable = false)
    private LocalDateTime refundDate;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "total_refund_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalRefundAmount;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "refund", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<RefundItem> refundItems = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (refundDate == null) {
            refundDate = LocalDateTime.now();
        }
        if (isActive == null) {
            isActive = true;
        }
        if (refundItems == null) {
            refundItems = new ArrayList<>();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Sale getSale() { return sale; }
    public void setSale(Sale sale) { this.sale = sale; }

    public User getRefundedBy() { return refundedBy; }
    public void setRefundedBy(User refundedBy) { this.refundedBy = refundedBy; }

    public LocalDateTime getRefundDate() { return refundDate; }
    public void setRefundDate(LocalDateTime refundDate) { this.refundDate = refundDate; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public BigDecimal getTotalRefundAmount() { return totalRefundAmount; }
    public void setTotalRefundAmount(BigDecimal totalRefundAmount) { this.totalRefundAmount = totalRefundAmount; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<RefundItem> getRefundItems() { return refundItems; }
    public void setRefundItems(List<RefundItem> refundItems) { this.refundItems = refundItems; }
}
