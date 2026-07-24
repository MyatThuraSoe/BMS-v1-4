package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CostHistoryDto {
    private LocalDate purchaseDate;
    private String supplierName;
    private Long supplierId;
    private Integer quantity;
    private BigDecimal unitCost;
    private BigDecimal currentSellingPrice;
    private BigDecimal impliedMarginPercent;

    public CostHistoryDto() {}

    public CostHistoryDto(LocalDate purchaseDate, String supplierName, Long supplierId,
                          Integer quantity, BigDecimal unitCost,
                          BigDecimal currentSellingPrice, BigDecimal impliedMarginPercent) {
        this.purchaseDate = purchaseDate;
        this.supplierName = supplierName;
        this.supplierId = supplierId;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.currentSellingPrice = currentSellingPrice;
        this.impliedMarginPercent = impliedMarginPercent;
    }

    public LocalDate getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(LocalDate purchaseDate) { this.purchaseDate = purchaseDate; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    public BigDecimal getCurrentSellingPrice() { return currentSellingPrice; }
    public void setCurrentSellingPrice(BigDecimal currentSellingPrice) { this.currentSellingPrice = currentSellingPrice; }
    public BigDecimal getImpliedMarginPercent() { return impliedMarginPercent; }
    public void setImpliedMarginPercent(BigDecimal impliedMarginPercent) { this.impliedMarginPercent = impliedMarginPercent; }
}