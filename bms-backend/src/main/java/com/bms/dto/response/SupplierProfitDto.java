package com.bms.dto.response;

import java.math.BigDecimal;

public class SupplierProfitDto {
    private Long supplierId;
    private String supplierName;
    private BigDecimal totalSuppliedCost;
    private BigDecimal estimatedRevenue;
    private BigDecimal estimatedProfit;
    private BigDecimal estimatedMarginPercent;

    public SupplierProfitDto() {}

    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public BigDecimal getTotalSuppliedCost() { return totalSuppliedCost; }
    public void setTotalSuppliedCost(BigDecimal totalSuppliedCost) { this.totalSuppliedCost = totalSuppliedCost; }
    public BigDecimal getEstimatedRevenue() { return estimatedRevenue; }
    public void setEstimatedRevenue(BigDecimal estimatedRevenue) { this.estimatedRevenue = estimatedRevenue; }
    public BigDecimal getEstimatedProfit() { return estimatedProfit; }
    public void setEstimatedProfit(BigDecimal estimatedProfit) { this.estimatedProfit = estimatedProfit; }
    public BigDecimal getEstimatedMarginPercent() { return estimatedMarginPercent; }
    public void setEstimatedMarginPercent(BigDecimal estimatedMarginPercent) { this.estimatedMarginPercent = estimatedMarginPercent; }
}