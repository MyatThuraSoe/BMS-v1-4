package com.bms.dto.response;

import java.math.BigDecimal;

public class ProductSalesSummaryDto {
    private Long totalQuantitySold;
    private BigDecimal totalRevenue;
    private BigDecimal totalProfit;
    private BigDecimal profitMarginPercent;

    public ProductSalesSummaryDto() {}

    public ProductSalesSummaryDto(Long totalQuantitySold, BigDecimal totalRevenue,
                                   BigDecimal totalProfit, BigDecimal profitMarginPercent) {
        this.totalQuantitySold = totalQuantitySold;
        this.totalRevenue = totalRevenue;
        this.totalProfit = totalProfit;
        this.profitMarginPercent = profitMarginPercent;
    }

    public Long getTotalQuantitySold() { return totalQuantitySold; }
    public void setTotalQuantitySold(Long totalQuantitySold) { this.totalQuantitySold = totalQuantitySold; }

    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(BigDecimal totalRevenue) { this.totalRevenue = totalRevenue; }

    public BigDecimal getTotalProfit() { return totalProfit; }
    public void setTotalProfit(BigDecimal totalProfit) { this.totalProfit = totalProfit; }

    public BigDecimal getProfitMarginPercent() { return profitMarginPercent; }
    public void setProfitMarginPercent(BigDecimal profitMarginPercent) { this.profitMarginPercent = profitMarginPercent; }
}
