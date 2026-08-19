package com.bms.dto.receipt;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ArPaymentReceiptDto {

    private Long paymentId;
    private String invoiceNumber;
    private String customerName;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private LocalDateTime paymentDate;
    private String recordedByName;
    private String notes;

    public ArPaymentReceiptDto() {
    }

    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }

    public LocalDateTime getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDateTime paymentDate) { this.paymentDate = paymentDate; }

    public String getRecordedByName() { return recordedByName; }
    public void setRecordedByName(String recordedByName) { this.recordedByName = recordedByName; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}