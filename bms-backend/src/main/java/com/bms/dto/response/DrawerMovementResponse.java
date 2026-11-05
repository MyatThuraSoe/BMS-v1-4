package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class DrawerMovementResponse {
    private Long id;
    private Long drawerSessionId;
    private String movementType;
    private BigDecimal amount;
    private String referenceType;
    private Long referenceId;
    private Long performedById;
    private String performedByName;
    private Long employeeShiftId;
    private Long cashierId;
    private String cashierName;
    private String reason;
    private LocalDateTime occurredAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDrawerSessionId() { return drawerSessionId; }
    public void setDrawerSessionId(Long drawerSessionId) { this.drawerSessionId = drawerSessionId; }

    public String getMovementType() { return movementType; }
    public void setMovementType(String movementType) { this.movementType = movementType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }

    public Long getReferenceId() { return referenceId; }
    public void setReferenceId(Long referenceId) { this.referenceId = referenceId; }

    public Long getPerformedById() { return performedById; }
    public void setPerformedById(Long performedById) { this.performedById = performedById; }

    public String getPerformedByName() { return performedByName; }
    public void setPerformedByName(String performedByName) { this.performedByName = performedByName; }

    public Long getEmployeeShiftId() { return employeeShiftId; }
    public void setEmployeeShiftId(Long employeeShiftId) { this.employeeShiftId = employeeShiftId; }

    public Long getCashierId() { return cashierId; }
    public void setCashierId(Long cashierId) { this.cashierId = cashierId; }

    public String getCashierName() { return cashierName; }
    public void setCashierName(String cashierName) { this.cashierName = cashierName; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
