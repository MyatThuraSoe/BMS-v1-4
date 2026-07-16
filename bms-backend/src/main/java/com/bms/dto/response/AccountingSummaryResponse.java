package com.bms.dto.response;

import java.math.BigDecimal;
import java.util.List;

public class AccountingSummaryResponse {
    private BigDecimal totalIncome;
    private BigDecimal totalCogs;
    private BigDecimal grossProfit;
    private BigDecimal totalExpenses;
    private BigDecimal netProfit;
    private List<CategoryAmount> expensesByCategory;
    private BigDecimal totalRefunds;

    public AccountingSummaryResponse() {
    }

    public AccountingSummaryResponse(BigDecimal totalIncome, BigDecimal totalCogs, BigDecimal grossProfit,
                                     BigDecimal totalExpenses, BigDecimal netProfit,
                                     List<CategoryAmount> expensesByCategory, BigDecimal totalRefunds) {
        this.totalIncome = totalIncome;
        this.totalCogs = totalCogs;
        this.grossProfit = grossProfit;
        this.totalExpenses = totalExpenses;
        this.netProfit = netProfit;
        this.expensesByCategory = expensesByCategory;
        this.totalRefunds = totalRefunds;
    }

    public BigDecimal getTotalIncome() {
        return totalIncome;
    }

    public void setTotalIncome(BigDecimal totalIncome) {
        this.totalIncome = totalIncome;
    }

    public BigDecimal getTotalCogs() {
        return totalCogs;
    }

    public void setTotalCogs(BigDecimal totalCogs) {
        this.totalCogs = totalCogs;
    }

    public BigDecimal getGrossProfit() {
        return grossProfit;
    }

    public void setGrossProfit(BigDecimal grossProfit) {
        this.grossProfit = grossProfit;
    }

    public BigDecimal getTotalExpenses() {
        return totalExpenses;
    }

    public void setTotalExpenses(BigDecimal totalExpenses) {
        this.totalExpenses = totalExpenses;
    }

    public BigDecimal getNetProfit() {
        return netProfit;
    }

    public void setNetProfit(BigDecimal netProfit) {
        this.netProfit = netProfit;
    }

    public List<CategoryAmount> getExpensesByCategory() {
        return expensesByCategory;
    }

    public void setExpensesByCategory(List<CategoryAmount> expensesByCategory) {
        this.expensesByCategory = expensesByCategory;
    }

    public BigDecimal getTotalRefunds() {
        return totalRefunds;
    }

    public void setTotalRefunds(BigDecimal totalRefunds) {
        this.totalRefunds = totalRefunds;
    }

    public static class CategoryAmount {
        private String category;
        private BigDecimal amount;

        public CategoryAmount() {
        }

        public CategoryAmount(String category, BigDecimal amount) {
            this.category = category;
            this.amount = amount;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }
    }
}
