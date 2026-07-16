package com.bms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_items", indexes = {
    @Index(name = "idx_refund_item_refund", columnList = "refund_id"),
    @Index(name = "idx_refund_item_sale_item", columnList = "sale_item_id")
})
public class RefundItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id", nullable = false)
    private Refund refund;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_item_id", nullable = false)
    private SaleItem saleItem;

    @Column(name = "quantity_refunded", nullable = false)
    private Integer quantityRefunded;

    @Column(name = "refund_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Refund getRefund() { return refund; }
    public void setRefund(Refund refund) { this.refund = refund; }

    public SaleItem getSaleItem() { return saleItem; }
    public void setSaleItem(SaleItem saleItem) { this.saleItem = saleItem; }

    public Integer getQuantityRefunded() { return quantityRefunded; }
    public void setQuantityRefunded(Integer quantityRefunded) { this.quantityRefunded = quantityRefunded; }

    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
