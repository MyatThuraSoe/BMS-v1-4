package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * P8: one received purchase line. Shows the purchase unit cost (invoice currency),
 * the landed cost and base-currency cost alongside it, labels which cost the primary
 * column is, and the resulting weighted-average cost after the receipt. The former
 * impliedMarginPercent (today's selling price vs a historical cost) was removed as
 * misleading (P8 req 8).
 */
public class CostHistoryDto {
    private LocalDate purchaseDate;
    private String supplierName;
    private Long supplierId;
    private Integer quantity;
    private BigDecimal unitCost;
    private BigDecimal landedUnitCost;
    private BigDecimal baseUnitCost;
    private String costType;
    private BigDecimal resultingWac;

    public CostHistoryDto() {}

    public CostHistoryDto(LocalDate purchaseDate, String supplierName, Long supplierId,
                          Integer quantity, BigDecimal unitCost,
                          BigDecimal landedUnitCost, BigDecimal baseUnitCost,
                          String costType, BigDecimal resultingWac) {
        this.purchaseDate = purchaseDate;
        this.supplierName = supplierName;
        this.supplierId = supplierId;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.landedUnitCost = landedUnitCost;
        this.baseUnitCost = baseUnitCost;
        this.costType = costType;
        this.resultingWac = resultingWac;
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
    public BigDecimal getLandedUnitCost() { return landedUnitCost; }
    public void setLandedUnitCost(BigDecimal landedUnitCost) { this.landedUnitCost = landedUnitCost; }
    public BigDecimal getBaseUnitCost() { return baseUnitCost; }
    public void setBaseUnitCost(BigDecimal baseUnitCost) { this.baseUnitCost = baseUnitCost; }
    public String getCostType() { return costType; }
    public void setCostType(String costType) { this.costType = costType; }
    public BigDecimal getResultingWac() { return resultingWac; }
    public void setResultingWac(BigDecimal resultingWac) { this.resultingWac = resultingWac; }
}