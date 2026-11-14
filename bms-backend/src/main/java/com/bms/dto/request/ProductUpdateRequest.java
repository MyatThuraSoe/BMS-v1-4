package com.bms.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * C4: product update can no longer change cost or stock. Cost moves only
 * through the reasoned cost-adjustment endpoint; stock moves only through the
 * adjust-stock flow. {@code version} is the optimistic-lock token the client
 * read with the product; a stale value is rejected so an old form cannot roll
 * back a receipt's data.
 */
public class ProductUpdateRequest {
    @NotBlank(message = "SKU is required")
    private String sku;

    @NotBlank(message = "Product name is required")
    private String name;

    private String description;

    private Long categoryId;

    @NotNull(message = "Unit price is required")
    @DecimalMin(value = "0.0", message = "Unit price cannot be negative")
    private BigDecimal unitPrice;

    private Integer minStockLevel;

    private String unit;

    private String barcode;

    private Boolean hasExpiry;

    private LocalDate expiryDate;

    private String binLocation;

    private String abcClass;

    private BigDecimal weight;

    @NotNull(message = "Product version is required to update")
    private Long version;

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public Integer getMinStockLevel() { return minStockLevel; }
    public void setMinStockLevel(Integer minStockLevel) { this.minStockLevel = minStockLevel; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public Boolean getHasExpiry() { return hasExpiry; }
    public void setHasExpiry(Boolean hasExpiry) { this.hasExpiry = hasExpiry; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public String getBinLocation() { return binLocation; }
    public void setBinLocation(String binLocation) { this.binLocation = binLocation; }
    public String getAbcClass() { return abcClass; }
    public void setAbcClass(String abcClass) { this.abcClass = abcClass; }
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}