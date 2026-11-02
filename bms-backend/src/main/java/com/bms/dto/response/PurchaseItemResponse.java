package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PurchaseItemResponse {
    private Long id;
    private Long productId;
    private String productName;
    private Integer quantity;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private Integer receivedQuantity;
    private Integer returnedQuantity;
    private BigDecimal landedUnitCost;
    private BigDecimal baseUnitCost;
    private BigDecimal exchangeRate;
    private String binLocation;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    public BigDecimal getTotalCost() { return totalCost; }
    public void setTotalCost(BigDecimal totalCost) { this.totalCost = totalCost; }
    public Integer getReceivedQuantity() { return receivedQuantity; }
    public void setReceivedQuantity(Integer receivedQuantity) { this.receivedQuantity = receivedQuantity; }
    public Integer getReturnedQuantity() { return returnedQuantity; }
    public void setReturnedQuantity(Integer returnedQuantity) { this.returnedQuantity = returnedQuantity; }
    public BigDecimal getLandedUnitCost() { return landedUnitCost; }
    public void setLandedUnitCost(BigDecimal landedUnitCost) { this.landedUnitCost = landedUnitCost; }
    public BigDecimal getBaseUnitCost() { return baseUnitCost; }
    public void setBaseUnitCost(BigDecimal baseUnitCost) { this.baseUnitCost = baseUnitCost; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public void setExchangeRate(BigDecimal exchangeRate) { this.exchangeRate = exchangeRate; }
    public String getBinLocation() { return binLocation; }
    public void setBinLocation(String binLocation) { this.binLocation = binLocation; }
}
