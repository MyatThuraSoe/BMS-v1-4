package com.bms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Table(name = "purchase_expenses", indexes = {
    @Index(name = "idx_purchase_expense_purchase", columnList = "purchase_id")
})
public class PurchaseExpense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_id", nullable = false)
    @JsonIgnore
    private Purchase purchase;

    @Column(nullable = false, length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_type", nullable = false, length = 30)
    private ExpenseType type = ExpenseType.FREIGHT;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_method", nullable = false, length = 30)
    private AllocationMethod allocationMethod = AllocationMethod.VALUE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum ExpenseType {
        FREIGHT, CUSTOMS, INSURANCE, HANDLING, OTHER
    }

    public enum AllocationMethod {
        VALUE, QUANTITY, WEIGHT
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (type == null) type = ExpenseType.FREIGHT;
        if (allocationMethod == null) allocationMethod = AllocationMethod.VALUE;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Purchase getPurchase() { return purchase; }
    public void setPurchase(Purchase purchase) { this.purchase = purchase; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ExpenseType getType() { return type != null ? type : ExpenseType.FREIGHT; }
    public void setType(ExpenseType type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public AllocationMethod getAllocationMethod() {
        return allocationMethod != null ? allocationMethod : AllocationMethod.VALUE;
    }
    public void setAllocationMethod(AllocationMethod allocationMethod) { this.allocationMethod = allocationMethod; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
