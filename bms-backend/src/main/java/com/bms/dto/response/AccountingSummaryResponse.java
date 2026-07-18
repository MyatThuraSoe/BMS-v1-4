package com.bms.dto.response;

import java.math.BigDecimal;
import java.util.List;

public class AccountingSummaryResponse {
    private BigDecimal totalIncome = BigDecimal.ZERO;
    private BigDecimal totalCogs = BigDecimal.ZERO;
    private BigDecimal grossProfit = BigDecimal.ZERO;
    private BigDecimal totalExpenses = BigDecimal.ZERO;
    private BigDecimal netProfit = BigDecimal.ZERO;
    private List<ExpenseCategorySummary> expensesByCategory;
    private BigDecimal totalRefunds = BigDecimal.ZERO;

    public BigDecimal getTotalIncome() { return totalIncome; }
    public void setTotalIncome(BigDecimal totalIncome) { this.totalIncome = totalIncome; }
    public BigDecimal getTotalCogs() { return totalCogs; }
    public void setTotalCogs(BigDecimal totalCogs) { this.totalCogs = totalCogs; }
    public BigDecimal getGrossProfit() { return grossProfit; }
    public void setGrossProfit(BigDecimal grossProfit) { this.grossProfit = grossProfit; }
    public BigDecimal getTotalExpenses() { return totalExpenses; }
    public void setTotalExpenses(BigDecimal totalExpenses) { this.totalExpenses = totalExpenses; }
    public BigDecimal getNetProfit() { return netProfit; }
    public void setNetProfit(BigDecimal netProfit) { this.netProfit = netProfit; }
    public List<ExpenseCategorySummary> getExpensesByCategory() { return expensesByCategory; }
    public void setExpensesByCategory(List<ExpenseCategorySummary> expensesByCategory) { this.expensesByCategory = expensesByCategory; }
    public BigDecimal getTotalRefunds() { return totalRefunds; }
    public void setTotalRefunds(BigDecimal totalRefunds) { this.totalRefunds = totalRefunds; }

    public static class ExpenseCategorySummary {
        private String category;
        private BigDecimal amount = BigDecimal.ZERO;

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }
}
