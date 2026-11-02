package com.bms.dto.response;

import java.math.BigDecimal;

public class SaleItemResponse {
    private Long id;
    private Long productId;
    private String productName;
    private String unit;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private BigDecimal taxAmount;
    private BigDecimal costPriceAtSale;
    private Integer quantityRefunded;
    private BigDecimal originalUnitPrice;
    private BigDecimal lineDiscount;
    private Long promotionId;
    private String promotionName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getTotalPrice() { return totalPrice; }
    public void setTotalPrice(BigDecimal totalPrice) { this.totalPrice = totalPrice; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public BigDecimal getCostPriceAtSale() { return costPriceAtSale; }
    public void setCostPriceAtSale(BigDecimal costPriceAtSale) { this.costPriceAtSale = costPriceAtSale; }
    public Integer getQuantityRefunded() { return quantityRefunded; }
    public void setQuantityRefunded(Integer quantityRefunded) { this.quantityRefunded = quantityRefunded; }
    public BigDecimal getOriginalUnitPrice() { return originalUnitPrice; }
    public void setOriginalUnitPrice(BigDecimal originalUnitPrice) { this.originalUnitPrice = originalUnitPrice; }
    public BigDecimal getLineDiscount() { return lineDiscount; }
    public void setLineDiscount(BigDecimal lineDiscount) { this.lineDiscount = lineDiscount; }
    public Long getPromotionId() { return promotionId; }
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }
    public String getPromotionName() { return promotionName; }
    public void setPromotionName(String promotionName) { this.promotionName = promotionName; }
}
