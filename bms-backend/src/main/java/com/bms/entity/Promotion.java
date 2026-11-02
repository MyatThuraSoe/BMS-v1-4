package com.bms.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Table(name = "promotions", indexes = {
    @Index(name = "idx_promotion_active", columnList = "is_active"),
    @Index(name = "idx_promotion_window", columnList = "start_date, end_date"),
    @Index(name = "idx_promotion_product", columnList = "product_id"),
    @Index(name = "idx_promotion_category", columnList = "category_id")
})
public class Promotion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PromotionType type = PromotionType.PERCENT_DISCOUNT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PromotionScope scope = PromotionScope.ALL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** BOGO: quantity the customer must buy. */
    @Column(name = "buy_quantity")
    private Integer buyQuantity;

    /** BOGO: quantity given free. */
    @Column(name = "get_quantity")
    private Integer getQuantity;

    @Column(name = "min_quantity")
    private Integer minQuantity = 1;

    /** PERCENT_DISCOUNT / FLASH_SALE: percent; FIXED_DISCOUNT: amount per unit. */
    @Column(name = "discount_value", precision = 10, scale = 2)
    private BigDecimal discountValue = BigDecimal.ZERO;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    /** Higher priority wins when two promotions produce the same price. */
    @Column(nullable = false)
    private Integer priority = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "promotion", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PromotionTier> tiers = new ArrayList<>();

    public enum PromotionType {
        PERCENT_DISCOUNT, FIXED_DISCOUNT, BOGO, TIERED, FLASH_SALE
    }

    public enum PromotionScope {
        ALL, PRODUCT, CATEGORY
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isActive == null) isActive = true;
        if (minQuantity == null) minQuantity = 1;
        if (priority == null) priority = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public PromotionType getType() { return type != null ? type : PromotionType.PERCENT_DISCOUNT; }
    public void setType(PromotionType type) { this.type = type; }
    public PromotionScope getScope() { return scope != null ? scope : PromotionScope.ALL; }
    public void setScope(PromotionScope scope) { this.scope = scope; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public Integer getBuyQuantity() { return buyQuantity; }
    public void setBuyQuantity(Integer buyQuantity) { this.buyQuantity = buyQuantity; }
    public Integer getGetQuantity() { return getQuantity; }
    public void setGetQuantity(Integer getQuantity) { this.getQuantity = getQuantity; }
    public Integer getMinQuantity() { return minQuantity != null ? minQuantity : 1; }
    public void setMinQuantity(Integer minQuantity) { this.minQuantity = minQuantity; }
    public BigDecimal getDiscountValue() { return discountValue != null ? discountValue : BigDecimal.ZERO; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }
    public LocalDateTime getStartDate() { return startDate; }
    public void setStartDate(LocalDateTime startDate) { this.startDate = startDate; }
    public LocalDateTime getEndDate() { return endDate; }
    public void setEndDate(LocalDateTime endDate) { this.endDate = endDate; }
    public Integer getPriority() { return priority != null ? priority : 0; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<PromotionTier> getTiers() { return tiers; }
    public void setTiers(List<PromotionTier> tiers) { this.tiers = tiers; }
}
