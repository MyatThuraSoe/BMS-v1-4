package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_price_history", indexes = {
    @Index(name = "idx_price_history_product", columnList = "product_id")
})
public class ProductPriceHistory {

    public enum PriceType {
        SELLING,
        COST
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "old_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal oldPrice;

    @Column(name = "new_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal newPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by", nullable = true)
    private User changedBy;

    @Column(name = "purchase_supplier_name", length = 255, nullable = true)
    private String purchaseSupplierName;

    @Column(name = "purchase_quantity", nullable = true)
    private Integer purchaseQuantity;

    @Column(name = "purchase_unit_price", precision = 10, scale = 2, nullable = true)
    private BigDecimal purchaseUnitPrice;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    /**
     * Distinguishes whether this record is a selling price change or a cost price change.
     * Nullable for backward compatibility — existing rows without this column are treated as SELLING.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", length = 20, nullable = true)
    private PriceType priceType;

    @PrePersist
    protected void onCreate() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public BigDecimal getOldPrice() { return oldPrice; }
    public void setOldPrice(BigDecimal oldPrice) { this.oldPrice = oldPrice; }

    public BigDecimal getNewPrice() { return newPrice; }
    public void setNewPrice(BigDecimal newPrice) { this.newPrice = newPrice; }

    public User getChangedBy() { return changedBy; }
    public void setChangedBy(User changedBy) { this.changedBy = changedBy; }

    public String getPurchaseSupplierName() { return purchaseSupplierName; }
    public void setPurchaseSupplierName(String purchaseSupplierName) { this.purchaseSupplierName = purchaseSupplierName; }

    public Integer getPurchaseQuantity() { return purchaseQuantity; }
    public void setPurchaseQuantity(Integer purchaseQuantity) { this.purchaseQuantity = purchaseQuantity; }

    public BigDecimal getPurchaseUnitPrice() { return purchaseUnitPrice; }
    public void setPurchaseUnitPrice(BigDecimal purchaseUnitPrice) { this.purchaseUnitPrice = purchaseUnitPrice; }

    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }

    public PriceType getPriceType() { return priceType; }
    public void setPriceType(PriceType priceType) { this.priceType = priceType; }
}
