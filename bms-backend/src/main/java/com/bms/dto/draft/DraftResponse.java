package com.bms.dto.draft;

import java.time.LocalDateTime;
import java.util.List;

public class DraftResponse {
    private Long id;
    private LocalDateTime createdAt;
    private Long customerId;
    private String notes;
    private List<Item> items;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public static class Item {
        private Long productId;
        private String productName;
        private String sku;
        private java.math.BigDecimal unitPrice;
        private Integer quantity;
        private Integer availableStock;
        public Long getProductId() { return productId; }
        public void setProductId(Long v) { productId = v; }
        public String getProductName() { return productName; }
        public void setProductName(String v) { productName = v; }
        public String getSku() { return sku; }
        public void setSku(String v) { sku = v; }
        public java.math.BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(java.math.BigDecimal v) { unitPrice = v; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer v) { quantity = v; }
        public Integer getAvailableStock() { return availableStock; }
        public void setAvailableStock(Integer v) { availableStock = v; }
    }
}