package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Sales heat map for one product, bucketed day-of-week x hour-of-day
 * (same shape the existing SalesHeatmap component consumes for the
 * shop-wide report, so the grid renderer is reused as-is).
 */
public class ProductSaleHeatmapDto {

    private String period;
    private LocalDate fromDate;
    private LocalDate toDate;
    private long totalQuantity;
    private BigDecimal totalRevenue;
    private long transactionCount;
    private List<Cell> cells = new ArrayList<>();

    public static class Cell {
        private int dayOfWeek;   // 1=Mon .. 7=Sun
        private int hourOfDay;   // 0..23
        private long quantity;
        private BigDecimal revenue;
        private long transactionCount;

        public int getDayOfWeek() { return dayOfWeek; }
        public void setDayOfWeek(int dayOfWeek) { this.dayOfWeek = dayOfWeek; }
        public int getHourOfDay() { return hourOfDay; }
        public void setHourOfDay(int hourOfDay) { this.hourOfDay = hourOfDay; }
        public long getQuantity() { return quantity; }
        public void setQuantity(long quantity) { this.quantity = quantity; }
        public BigDecimal getRevenue() { return revenue; }
        public void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
        public long getTransactionCount() { return transactionCount; }
        public void setTransactionCount(long transactionCount) { this.transactionCount = transactionCount; }
    }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate fromDate) { this.fromDate = fromDate; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate toDate) { this.toDate = toDate; }
    public long getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(long totalQuantity) { this.totalQuantity = totalQuantity; }
    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(BigDecimal totalRevenue) { this.totalRevenue = totalRevenue; }
    public long getTransactionCount() { return transactionCount; }
    public void setTransactionCount(long transactionCount) { this.transactionCount = transactionCount; }
    public List<Cell> getCells() { return cells; }
    public void setCells(List<Cell> cells) { this.cells = cells; }
}
