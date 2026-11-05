package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class SalePaymentResponse {
    private Long id;
    private String paymentMethod;
    private BigDecimal amount;
    private String externalReference;
    private String paymentStatus;
    private Long drawerSessionId;
    private Long drawerMovementId;
    private LocalDateTime paidAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }

    public Long getDrawerSessionId() { return drawerSessionId; }
    public void setDrawerSessionId(Long drawerSessionId) { this.drawerSessionId = drawerSessionId; }

    public Long getDrawerMovementId() { return drawerMovementId; }
    public void setDrawerMovementId(Long drawerMovementId) { this.drawerMovementId = drawerMovementId; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
}
