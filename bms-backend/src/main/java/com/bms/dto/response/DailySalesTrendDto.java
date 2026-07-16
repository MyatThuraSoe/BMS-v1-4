package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public class DailySalesTrendDto {
    private LocalDate date;
    private BigDecimal totalSales;
    private int transactionCount;

    public DailySalesTrendDto() {}

    public DailySalesTrendDto(LocalDate date, BigDecimal totalSales, int transactionCount) {
        this.date = date;
        this.totalSales = totalSales;
        this.transactionCount = transactionCount;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public BigDecimal getTotalSales() {
        return totalSales;
    }

    public void setTotalSales(BigDecimal totalSales) {
        this.totalSales = totalSales;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(int transactionCount) {
        this.transactionCount = transactionCount;
    }
}
