package com.bms.dto.request;

import jakarta.validation.constraints.NotBlank;

public class PurchasePaymentStatusUpdateRequest {

    @NotBlank(message = "Payment status is required")
    private String paymentStatus;

    public @NotBlank(message = "Payment status is required") String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }
}