package com.bms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public class ExpenseRequest {
    @NotNull(message = "Category is required")
    private String category;

    @NotBlank(message = "Description is required")
    private String description;

    @NotNull(message = "Amount is required")
    private BigDecimal amount;

    @NotNull(message = "Expense date is required")
    private LocalDate expenseDate;

    /** O4: true when this cost was already capitalised into a purchase. */
    private Boolean capitalized;

    /** O4: optional link to the purchase_expenses row that capitalised it. */
    private Long purchaseExpenseId;

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDate getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }

    public Boolean getCapitalized() { return capitalized; }
    public void setCapitalized(Boolean capitalized) { this.capitalized = capitalized; }

    public Long getPurchaseExpenseId() { return purchaseExpenseId; }
    public void setPurchaseExpenseId(Long purchaseExpenseId) { this.purchaseExpenseId = purchaseExpenseId; }
}
