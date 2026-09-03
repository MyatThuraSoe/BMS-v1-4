package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Unified DTO for displaying both cost and selling price changes in chronological order.
 * Combines data from ProductPriceHistory and PurchaseItem records.
 */
public class UnifiedPriceHistoryDto {
    
    public enum ChangeType {
        COST,           // Change in cost price (from purchases)
        SELLING         // Change in selling price (manual price adjustments)
    }
    
    private LocalDateTime changedAt;
    private ChangeType changeType;
    private BigDecimal oldValue;
    private BigDecimal newValue;
    private BigDecimal changeAmount;      // newValue - oldValue
    private BigDecimal changePercent;     // ((newValue - oldValue) / oldValue) * 100
    private String changedByUsername;
    
    // Additional context fields
    private String supplierName;          // For COST changes only
    private Integer quantity;             // For COST changes only (quantity purchased)
    private BigDecimal purchaseUnitPrice; // Purchase unit price for purchase context

    public UnifiedPriceHistoryDto() {}

    public UnifiedPriceHistoryDto(LocalDateTime changedAt, ChangeType changeType, 
                                   BigDecimal oldValue, BigDecimal newValue,
                                   String changedByUsername) {
        this(changedAt, changeType, oldValue, newValue, changedByUsername, null, null);
    }

    public UnifiedPriceHistoryDto(LocalDateTime changedAt, ChangeType changeType, 
                                   BigDecimal oldValue, BigDecimal newValue,
                                   String changedByUsername, String supplierName, Integer quantity) {
        this(changedAt, changeType, oldValue, newValue, changedByUsername, supplierName, quantity, null);
    }

    public UnifiedPriceHistoryDto(LocalDateTime changedAt, ChangeType changeType,
                                   BigDecimal oldValue, BigDecimal newValue,
                                   String changedByUsername, String supplierName,
                                   Integer quantity, BigDecimal purchaseUnitPrice) {
        this.changedAt = changedAt;
        this.changeType = changeType;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedByUsername = changedByUsername;
        this.supplierName = supplierName;
        this.quantity = quantity;
        this.purchaseUnitPrice = purchaseUnitPrice;
        
        // Calculate change amount and percentage
        if (oldValue != null && newValue != null) {
            this.changeAmount = newValue.subtract(oldValue);
            
            if (oldValue.compareTo(BigDecimal.ZERO) > 0) {
                this.changePercent = changeAmount
                        .multiply(BigDecimal.valueOf(100))
                        .divide(oldValue, 2, java.math.RoundingMode.HALF_UP);
            } else {
                this.changePercent = BigDecimal.ZERO;
            }
        }
    }

    // Getters and Setters
    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }

    public ChangeType getChangeType() { return changeType; }
    public void setChangeType(ChangeType changeType) { this.changeType = changeType; }

    public BigDecimal getOldValue() { return oldValue; }
    public void setOldValue(BigDecimal oldValue) { this.oldValue = oldValue; }

    public BigDecimal getNewValue() { return newValue; }
    public void setNewValue(BigDecimal newValue) { this.newValue = newValue; }

    public BigDecimal getChangeAmount() { return changeAmount; }
    public void setChangeAmount(BigDecimal changeAmount) { this.changeAmount = changeAmount; }

    public BigDecimal getChangePercent() { return changePercent; }
    public void setChangePercent(BigDecimal changePercent) { this.changePercent = changePercent; }

    public String getChangedByUsername() { return changedByUsername; }
    public void setChangedByUsername(String changedByUsername) { this.changedByUsername = changedByUsername; }

    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getPurchaseUnitPrice() { return purchaseUnitPrice; }
    public void setPurchaseUnitPrice(BigDecimal purchaseUnitPrice) { this.purchaseUnitPrice = purchaseUnitPrice; }
}
