package com.bms.dto.response;

import java.math.BigDecimal;
import java.util.List;

public class CartVerifyResponse {
    private boolean valid; // true = nothing changed, safe to check out immediately
    private List<CartVerifyItemResult> items;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private List<String> messages; // human-readable, e.g. "Coca-Cola 500ml price changed from $2.00 to $2.50"

    // getters/setters for all fields

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public List<CartVerifyItemResult> getItems() { return items; }
    public void setItems(List<CartVerifyItemResult> items) { this.items = items; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public List<String> getMessages() { return messages; }
    public void setMessages(List<String> messages) { this.messages = messages; }

    public static class CartVerifyItemResult {
        private Long productId;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;       // current authoritative price
        private BigDecimal taxRate;
        private Integer availableStock;
        private BigDecimal lineTotal;
        private boolean priceChanged;
        private boolean insufficientStock;

        // getters/setters for all fields
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
        public BigDecimal getTaxRate() { return taxRate; }
        public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }
        public Integer getAvailableStock() { return availableStock; }
        public void setAvailableStock(Integer availableStock) { this.availableStock = availableStock; }
        public BigDecimal getLineTotal() { return lineTotal; }
        public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = lineTotal; }
        public boolean isPriceChanged() { return priceChanged; }
        public void setPriceChanged(boolean priceChanged) { this.priceChanged = priceChanged; }
        public boolean isInsufficientStock() { return insufficientStock; }
        public void setInsufficientStock(boolean insufficientStock) { this.insufficientStock = insufficientStock; }
    }
}