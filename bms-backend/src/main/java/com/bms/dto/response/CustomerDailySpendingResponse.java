package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CustomerDailySpendingResponse {
    private LocalDate date;
    private BigDecimal totalAmount;

    public CustomerDailySpendingResponse(LocalDate date, BigDecimal totalAmount) {
        this.date = date;
        this.totalAmount = totalAmount;
    }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
}