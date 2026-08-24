package com.bms.dto.order;

import java.math.BigDecimal;
import java.time.LocalDate;

public class OrderConvertRequest {
    /** CASH or CREDIT. Defaults to CASH. */
    private String paymentMethod;

    /** Required for CASH conversion — full payment for the order total. */
    private BigDecimal amountPaid;

    /** Required for CREDIT conversion. */
    private LocalDate dueDate;

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public BigDecimal getAmountPaid() { return amountPaid; }
    public void setAmountPaid(BigDecimal amountPaid) { this.amountPaid = amountPaid; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
}