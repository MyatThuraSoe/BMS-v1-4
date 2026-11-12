package com.bms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class PaymentValidationRequest {

    @Valid
    @NotEmpty(message = "At least one payment is required")
    private List<SalePaymentRequest> payments;

    public List<SalePaymentRequest> getPayments() { return payments; }
    public void setPayments(List<SalePaymentRequest> payments) { this.payments = payments; }
}
