package com.bms.dto.response;

import com.bms.entity.Expense;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class ExpenseResponse {
    private Long id;
    private String category;
    private String description;
    private BigDecimal amount;
    private LocalDate expenseDate;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Boolean hasReceiptImage;

    public static ExpenseResponse fromEntity(Expense expense) {
        ExpenseResponse response = new ExpenseResponse();
        response.setId(expense.getId());
        response.setCategory(expense.getCategory() != null ? expense.getCategory().name() : null);
        response.setDescription(expense.getDescription());
        response.setAmount(expense.getAmount());
        response.setExpenseDate(expense.getExpenseDate());
        response.setCreatedBy(expense.getCreatedBy());
        response.setCreatedAt(expense.getCreatedAt());
        response.setHasReceiptImage(expense.getReceiptImage() != null);
        return response;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Boolean getHasReceiptImage() { return hasReceiptImage; }
    public void setHasReceiptImage(Boolean hasReceiptImage) { this.hasReceiptImage = hasReceiptImage; }
}
