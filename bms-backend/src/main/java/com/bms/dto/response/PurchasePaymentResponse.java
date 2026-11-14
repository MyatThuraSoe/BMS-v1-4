package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PurchasePaymentResponse {
    private Long id;
    private Long purchaseId;
    private String purchaseNumber;
    private BigDecimal amount;
    private LocalDateTime paymentDate;
    private Long recordedById;
    private String recordedByName;
    private String notes;
    private String paymentMethod;
    private Long employeeShiftId;
    private Long drawerMovementId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPurchaseId() { return purchaseId; }
    public void setPurchaseId(Long purchaseId) { this.purchaseId = purchaseId; }
    public String getPurchaseNumber() { return purchaseNumber; }
    public void setPurchaseNumber(String purchaseNumber) { this.purchaseNumber = purchaseNumber; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDateTime getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDateTime paymentDate) { this.paymentDate = paymentDate; }
    public Long getRecordedById() { return recordedById; }
    public void setRecordedById(Long recordedById) { this.recordedById = recordedById; }
    public String getRecordedByName() { return recordedByName; }
    public void setRecordedByName(String recordedByName) { this.recordedByName = recordedByName; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public Long getEmployeeShiftId() { return employeeShiftId; }
    public void setEmployeeShiftId(Long employeeShiftId) { this.employeeShiftId = employeeShiftId; }
    public Long getDrawerMovementId() { return drawerMovementId; }
    public void setDrawerMovementId(Long drawerMovementId) { this.drawerMovementId = drawerMovementId; }
}