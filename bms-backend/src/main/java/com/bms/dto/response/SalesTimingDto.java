package com.bms.dto.response;

import java.math.BigDecimal;

public class SalesTimingDto {
    private Integer dayOfWeek;   // 1=Sunday ... 7=Saturday (Java DayOfWeek: 1=MON..7=SUN, but we'll use 1-7 Mon-Sun)
    private Integer hourOfDay;   // 0-23
    private Long transactionCount;
    private BigDecimal totalRevenue;

    public SalesTimingDto() {}

    public SalesTimingDto(Integer dayOfWeek, Integer hourOfDay, Long transactionCount, BigDecimal totalRevenue) {
        this.dayOfWeek = dayOfWeek;
        this.hourOfDay = hourOfDay;
        this.transactionCount = transactionCount;
        this.totalRevenue = totalRevenue;
    }

    public Integer getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Integer dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public Integer getHourOfDay() { return hourOfDay; }
    public void setHourOfDay(Integer hourOfDay) { this.hourOfDay = hourOfDay; }

    public Long getTransactionCount() { return transactionCount; }
    public void setTransactionCount(Long transactionCount) { this.transactionCount = transactionCount; }

    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(BigDecimal totalRevenue) { this.totalRevenue = totalRevenue; }
}
