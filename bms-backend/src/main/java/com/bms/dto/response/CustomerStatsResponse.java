package com.bms.dto.response;
import java.math.BigDecimal;

public class CustomerStatsResponse {
    private BigDecimal totalSpentAllTime;
    private BigDecimal totalSpentThisWeek;
    private BigDecimal totalSpentThisMonth;
    private BigDecimal totalSpentThisYear;
    private Long totalInvoices;

    // Getters and Setters
    public BigDecimal getTotalSpentAllTime() { return totalSpentAllTime; }
    public void setTotalSpentAllTime(BigDecimal totalSpentAllTime) { this.totalSpentAllTime = totalSpentAllTime; }
    public BigDecimal getTotalSpentThisWeek() { return totalSpentThisWeek; }
    public void setTotalSpentThisWeek(BigDecimal totalSpentThisWeek) { this.totalSpentThisWeek = totalSpentThisWeek; }
    public BigDecimal getTotalSpentThisMonth() { return totalSpentThisMonth; }
    public void setTotalSpentThisMonth(BigDecimal totalSpentThisMonth) { this.totalSpentThisMonth = totalSpentThisMonth; }
    public BigDecimal getTotalSpentThisYear() { return totalSpentThisYear; }
    public void setTotalSpentThisYear(BigDecimal totalSpentThisYear) { this.totalSpentThisYear = totalSpentThisYear; }
    public Long getTotalInvoices() { return totalInvoices; }
    public void setTotalInvoices(Long totalInvoices) { this.totalInvoices = totalInvoices; }
}