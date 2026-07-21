package com.bms.dto.response;

import java.math.BigDecimal;

public class ReorderSuggestionDto {
    private Long productId;
    private String productName;
    private int currentStock;
    private int minStockLevel;
    private BigDecimal averageDailySales;
    private Double daysUntilStockout;
    private int suggestedReorderQuantity;
    private Long lastSupplierId;
    private String lastSupplierName;
    private BigDecimal lastPurchaseUnitCost;

    // Getters and Setters
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public int getCurrentStock() { return currentStock; }
    public void setCurrentStock(int currentStock) { this.currentStock = currentStock; }

    public int getMinStockLevel() { return minStockLevel; }
    public void setMinStockLevel(int minStockLevel) { this.minStockLevel = minStockLevel; }

    public BigDecimal getAverageDailySales() { return averageDailySales; }
    public void setAverageDailySales(BigDecimal averageDailySales) { this.averageDailySales = averageDailySales; }

    public Double getDaysUntilStockout() { return daysUntilStockout; }
    public void setDaysUntilStockout(Double daysUntilStockout) { this.daysUntilStockout = daysUntilStockout; }

    public int getSuggestedReorderQuantity() { return suggestedReorderQuantity; }
    public void setSuggestedReorderQuantity(int suggestedReorderQuantity) { this.suggestedReorderQuantity = suggestedReorderQuantity; }

    public Long getLastSupplierId() { return lastSupplierId; }
    public void setLastSupplierId(Long lastSupplierId) { this.lastSupplierId = lastSupplierId; }

    public String getLastSupplierName() { return lastSupplierName; }
    public void setLastSupplierName(String lastSupplierName) { this.lastSupplierName = lastSupplierName; }

    public BigDecimal getLastPurchaseUnitCost() { return lastPurchaseUnitCost; }
    public void setLastPurchaseUnitCost(BigDecimal lastPurchaseUnitCost) { this.lastPurchaseUnitCost = lastPurchaseUnitCost; }
}
