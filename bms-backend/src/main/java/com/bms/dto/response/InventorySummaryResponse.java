package com.bms.dto.response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class InventorySummaryResponse {

    private long totalProducts;
    private long activeProducts;
    private long inactiveProducts;

    private long inStockCount;
    private long lowStockCount;
    private long outOfStockCount;

    private long totalUnits;
    private BigDecimal costValue = BigDecimal.ZERO;
    private BigDecimal retailValue = BigDecimal.ZERO;
    private BigDecimal potentialProfit = BigDecimal.ZERO;

    private List<CategoryBreakdown> categoryBreakdown = new ArrayList<>();
    private List<LowStockItem> lowStockItems = new ArrayList<>();

    public static class CategoryBreakdown {
        private Long categoryId;
        private String categoryName;
        private long productCount;
        private long units;
        private BigDecimal costValue = BigDecimal.ZERO;
        private BigDecimal retailValue = BigDecimal.ZERO;

        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

        public long getProductCount() { return productCount; }
        public void setProductCount(long productCount) { this.productCount = productCount; }

        public long getUnits() { return units; }
        public void setUnits(long units) { this.units = units; }

        public BigDecimal getCostValue() { return costValue; }
        public void setCostValue(BigDecimal costValue) { this.costValue = costValue; }

        public BigDecimal getRetailValue() { return retailValue; }
        public void setRetailValue(BigDecimal retailValue) { this.retailValue = retailValue; }
    }

    public static class LowStockItem {
        private Long productId;
        private String productName;
        private String sku;
        private String categoryName;
        private int stockQuantity;
        private int minStockLevel;
        private int shortage;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }

        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }

        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }

        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

        public int getStockQuantity() { return stockQuantity; }
        public void setStockQuantity(int stockQuantity) { this.stockQuantity = stockQuantity; }

        public int getMinStockLevel() { return minStockLevel; }
        public void setMinStockLevel(int minStockLevel) { this.minStockLevel = minStockLevel; }

        public int getShortage() { return shortage; }
        public void setShortage(int shortage) { this.shortage = shortage; }
    }

    public long getTotalProducts() { return totalProducts; }
    public void setTotalProducts(long totalProducts) { this.totalProducts = totalProducts; }

    public long getActiveProducts() { return activeProducts; }
    public void setActiveProducts(long activeProducts) { this.activeProducts = activeProducts; }

    public long getInactiveProducts() { return inactiveProducts; }
    public void setInactiveProducts(long inactiveProducts) { this.inactiveProducts = inactiveProducts; }

    public long getInStockCount() { return inStockCount; }
    public void setInStockCount(long inStockCount) { this.inStockCount = inStockCount; }

    public long getLowStockCount() { return lowStockCount; }
    public void setLowStockCount(long lowStockCount) { this.lowStockCount = lowStockCount; }

    public long getOutOfStockCount() { return outOfStockCount; }
    public void setOutOfStockCount(long outOfStockCount) { this.outOfStockCount = outOfStockCount; }

    public long getTotalUnits() { return totalUnits; }
    public void setTotalUnits(long totalUnits) { this.totalUnits = totalUnits; }

    public BigDecimal getCostValue() { return costValue; }
    public void setCostValue(BigDecimal costValue) { this.costValue = costValue; }

    public BigDecimal getRetailValue() { return retailValue; }
    public void setRetailValue(BigDecimal retailValue) { this.retailValue = retailValue; }

    public BigDecimal getPotentialProfit() { return potentialProfit; }
    public void setPotentialProfit(BigDecimal potentialProfit) { this.potentialProfit = potentialProfit; }

    public List<CategoryBreakdown> getCategoryBreakdown() { return categoryBreakdown; }
    public void setCategoryBreakdown(List<CategoryBreakdown> categoryBreakdown) { this.categoryBreakdown = categoryBreakdown; }

    public List<LowStockItem> getLowStockItems() { return lowStockItems; }
    public void setLowStockItems(List<LowStockItem> lowStockItems) { this.lowStockItems = lowStockItems; }
}
