package com.bms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

public class PurchaseCreateRequest {

    private Long supplierId;

    @NotNull(message = "Purchase date is required")
    private String purchaseDate;

    @NotNull(message = "Items are required")
    @Valid
    private List<PurchaseItemRequest> items;

    private String notes;

    private BigDecimal discountAmount = BigDecimal.ZERO;

    private BigDecimal taxAmount = BigDecimal.ZERO;

    private String paymentStatus;

    /** ISO currency of the supplier invoice, e.g. USD. */
    private String currencyCode = "USD";

    /** Base-currency units per one unit of {@link #currencyCode}. */
    private BigDecimal exchangeRate = BigDecimal.ONE;

    /** Landed-cost lines (freight, customs, insurance...). */
    @Valid
    private List<ExpenseRequest> expenses;

    /**
     * When true (the default) the PO is received immediately and stock/WAC are
     * updated. Set false to create an "ordered" PO that is received later via
     * the receiving screen.
     */
    private Boolean receiveNow = true;

    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
    public String getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(String purchaseDate) { this.purchaseDate = purchaseDate; }
    public List<PurchaseItemRequest> getItems() { return items; }
    public void setItems(List<PurchaseItemRequest> items) { this.items = items; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public void setExchangeRate(BigDecimal exchangeRate) { this.exchangeRate = exchangeRate; }
    public List<ExpenseRequest> getExpenses() { return expenses; }
    public void setExpenses(List<ExpenseRequest> expenses) { this.expenses = expenses; }
    public Boolean getReceiveNow() { return receiveNow; }
    public void setReceiveNow(Boolean receiveNow) { this.receiveNow = receiveNow; }

    public static class PurchaseItemRequest {
        @NotNull(message = "Product ID is required")
        private Long productId;

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be positive")
        private Integer quantity;

        @NotNull(message = "Unit cost is required")
        @Positive(message = "Unit cost must be positive")
        private BigDecimal unitCost;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public BigDecimal getUnitCost() { return unitCost; }
        public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    }

    public static class ExpenseRequest {
        @NotBlank(message = "Expense description is required")
        private String description;

        /** FREIGHT, CUSTOMS, INSURANCE, HANDLING, OTHER. */
        private String type = "FREIGHT";

        @NotNull(message = "Expense amount is required")
        @Positive(message = "Expense amount must be positive")
        private BigDecimal amount;

        /** VALUE, QUANTITY or WEIGHT. */
        private String allocationMethod = "VALUE";

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getAllocationMethod() { return allocationMethod; }
        public void setAllocationMethod(String allocationMethod) { this.allocationMethod = allocationMethod; }
    }
}
