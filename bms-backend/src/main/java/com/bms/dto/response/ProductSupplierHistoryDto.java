package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public class ProductSupplierHistoryDto {
    private Long supplierId;
    private String supplierName;
    private Long timesPurchased;
    private BigDecimal mostRecentUnitCost;
    private LocalDate mostRecentPurchaseDate;
    private Integer totalQuantityPurchased;
    private BigDecimal totalAmountSpent;

    public ProductSupplierHistoryDto() {}

    public ProductSupplierHistoryDto(Long supplierId, String supplierName, Long timesPurchased,
                                     BigDecimal mostRecentUnitCost, LocalDate mostRecentPurchaseDate,
                                     Integer totalQuantityPurchased, BigDecimal totalAmountSpent) {
        this.supplierId = supplierId;
        this.supplierName = supplierName;
        this.timesPurchased = timesPurchased;
        this.mostRecentUnitCost = mostRecentUnitCost;
        this.mostRecentPurchaseDate = mostRecentPurchaseDate;
        this.totalQuantityPurchased = totalQuantityPurchased;
        this.totalAmountSpent = totalAmountSpent;
    }

    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }

    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }

    public Long getTimesPurchased() { return timesPurchased; }
    public void setTimesPurchased(Long timesPurchased) { this.timesPurchased = timesPurchased; }

    public BigDecimal getMostRecentUnitCost() { return mostRecentUnitCost; }
    public void setMostRecentUnitCost(BigDecimal mostRecentUnitCost) { this.mostRecentUnitCost = mostRecentUnitCost; }

    public LocalDate getMostRecentPurchaseDate() { return mostRecentPurchaseDate; }
    public void setMostRecentPurchaseDate(LocalDate mostRecentPurchaseDate) { this.mostRecentPurchaseDate = mostRecentPurchaseDate; }

    public Integer getTotalQuantityPurchased() { return totalQuantityPurchased; }
    public void setTotalQuantityPurchased(Integer totalQuantityPurchased) { this.totalQuantityPurchased = totalQuantityPurchased; }

    public BigDecimal getTotalAmountSpent() { return totalAmountSpent; }
    public void setTotalAmountSpent(BigDecimal totalAmountSpent) { this.totalAmountSpent = totalAmountSpent; }
}
