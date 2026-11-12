package com.bms.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** One payment line in a split-payment sale. */
public class SalePaymentRequest {

    @NotNull(message = "Payment method is required")
    private String method;

    @NotNull(message = "Payment amount is required")
    @PositiveOrZero(message = "Payment amount must be zero or positive")
    private BigDecimal amount;

    private String externalReference;

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }
}
