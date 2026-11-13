package com.bms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Table(name = "purchase_items", indexes = {
    @Index(name = "idx_purchase_item_purchase", columnList = "purchase_id"),
    @Index(name = "idx_purchase_item_product", columnList = "product_id")
})
public class PurchaseItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id", nullable = false)
    @JsonIgnore
    private Purchase purchase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "total_cost", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalCost;

    /** Units already received (supports partial receiving). */
    @Column(name = "received_quantity")
    private Integer receivedQuantity = 0;

    /** Units already returned to the supplier via RTV. */
    @Column(name = "returned_quantity")
    private Integer returnedQuantity = 0;

    /** Purchase-time FX rate snapshot (base units per invoice-currency unit). */
    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate = BigDecimal.ONE;

    /** Unit cost including allocated freight/duty/insurance. */
    @Column(name = "landed_unit_cost", precision = 19, scale = 6)
    private BigDecimal landedUnitCost;

    /** Landed unit cost converted to the shop's base currency. */
    @Column(name = "base_unit_cost", precision = 19, scale = 6)
    private BigDecimal baseUnitCost;

    @PrePersist
    protected void onCreate() {
        if (totalCost == null && unitCost != null && quantity != null) {
            totalCost = unitCost.multiply(new BigDecimal(quantity));
        }
    }



    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Purchase getPurchase() { return purchase; }
    public void setPurchase(Purchase purchase) { this.purchase = purchase; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }

    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }

    public Integer getReceivedQuantity() { return receivedQuantity != null ? receivedQuantity : 0; }
    public void setReceivedQuantity(Integer receivedQuantity) { this.receivedQuantity = receivedQuantity; }

    public Integer getReturnedQuantity() { return returnedQuantity != null ? returnedQuantity : 0; }
    public void setReturnedQuantity(Integer returnedQuantity) { this.returnedQuantity = returnedQuantity; }

    public BigDecimal getExchangeRate() {
        return exchangeRate != null && exchangeRate.compareTo(BigDecimal.ZERO) > 0
                ? exchangeRate : BigDecimal.ONE;
    }
    public void setExchangeRate(BigDecimal exchangeRate) { this.exchangeRate = exchangeRate; }

    public BigDecimal getLandedUnitCost() {
        return landedUnitCost != null ? landedUnitCost : unitCost;
    }
    public void setLandedUnitCost(BigDecimal landedUnitCost) { this.landedUnitCost = landedUnitCost; }

    public BigDecimal getBaseUnitCost() {
        return baseUnitCost != null ? baseUnitCost : getLandedUnitCost();
    }
    public void setBaseUnitCost(BigDecimal baseUnitCost) { this.baseUnitCost = baseUnitCost; }
}
