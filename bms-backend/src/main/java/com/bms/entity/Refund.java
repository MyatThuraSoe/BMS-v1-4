package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "refunds", indexes = {
    @Index(name = "idx_refund_sale", columnList = "sale_id"),
    @Index(name = "idx_refund_date", columnList = "refund_date"),
    @Index(name = "idx_refund_user", columnList = "refunded_by")
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

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "total_refund_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalRefundAmount;

    @OneToMany(mappedBy = "refund", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<RefundItem> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (refundDate == null) {
            refundDate = LocalDateTime.now();
        }
    }

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

    public List<RefundItem> getItems() { return items; }
    public void setItems(List<RefundItem> items) { this.items = items; }
}
