package com.bms.dto.response;

import java.math.BigDecimal;

public record UserStatsDto(
        long totalSales,
        BigDecimal totalRevenue,
        long todaySales,
        BigDecimal todayRevenue
) {}
