package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class CountSheetResponse {
    private Long id;
    private String sheetNumber;
    private String status;
    private String countType;
    private LocalDate scheduledDate;
    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime approvedAt;
    private String notes;
    private BigDecimal varianceValue;
    private Long createdBy;
    private Long approvedBy;
    private int itemCount;
    private List<Item> items = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSheetNumber() { return sheetNumber; }
    public void setSheetNumber(String sheetNumber) { this.sheetNumber = sheetNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCountType() { return countType; }
    public void setCountType(String countType) { this.countType = countType; }
    public LocalDate getScheduledDate() { return scheduledDate; }
    public void setScheduledDate(LocalDate scheduledDate) { this.scheduledDate = scheduledDate; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public BigDecimal getVarianceValue() { return varianceValue; }
    public void setVarianceValue(BigDecimal varianceValue) { this.varianceValue = varianceValue; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getApprovedBy() { return approvedBy; }
    public void setApprovedBy(Long approvedBy) { this.approvedBy = approvedBy; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public static class Item {
        private Long id;
        private Long productId;
        private String productName;
        private String sku;
        private String barcode;
        private String binLocation;
        private String unit;
        private BigDecimal unitPrice;
        private Integer systemQuantity;
        private Integer countedQuantity;
        private Integer variance;
        private String reasonCode;
        private BigDecimal valuationAmount;
        private String note;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        public String getBarcode() { return barcode; }
        public void setBarcode(String barcode) { this.barcode = barcode; }
        public String getBinLocation() { return binLocation; }
        public void setBinLocation(String binLocation) { this.binLocation = binLocation; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
        public Integer getSystemQuantity() { return systemQuantity; }
        public void setSystemQuantity(Integer systemQuantity) { this.systemQuantity = systemQuantity; }
        public Integer getCountedQuantity() { return countedQuantity; }
        public void setCountedQuantity(Integer countedQuantity) { this.countedQuantity = countedQuantity; }
        public Integer getVariance() { return variance; }
        public void setVariance(Integer variance) { this.variance = variance; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
        public BigDecimal getValuationAmount() { return valuationAmount; }
        public void setValuationAmount(BigDecimal valuationAmount) { this.valuationAmount = valuationAmount; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }
}
