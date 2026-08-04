package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class DeadStockDto {
    private Long productId;
    private String productName;
    private String categoryName;
    private Integer stockQuantity;
    private BigDecimal stockValue;
    private LocalDateTime lastSoldDate;
    private Long daysSinceLastSale;

    public DeadStockDto() {}

    public DeadStockDto(Long productId, String productName, String categoryName,
                        Integer stockQuantity, BigDecimal stockValue,
                        LocalDateTime lastSoldDate, Long daysSinceLastSale) {
        this.productId = productId;
        this.productName = productName;
        this.categoryName = categoryName;
        this.stockQuantity = stockQuantity;
        this.stockValue = stockValue;
        this.lastSoldDate = lastSoldDate;
        this.daysSinceLastSale = daysSinceLastSale;
    }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public Integer getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }

    public BigDecimal getStockValue() { return stockValue; }
    public void setStockValue(BigDecimal stockValue) { this.stockValue = stockValue; }

    public LocalDateTime getLastSoldDate() { return lastSoldDate; }
    public void setLastSoldDate(LocalDateTime lastSoldDate) { this.lastSoldDate = lastSoldDate; }

    public Long getDaysSinceLastSale() { return daysSinceLastSale; }
    public void setDaysSinceLastSale(Long daysSinceLastSale) { this.daysSinceLastSale = daysSinceLastSale; }
}
