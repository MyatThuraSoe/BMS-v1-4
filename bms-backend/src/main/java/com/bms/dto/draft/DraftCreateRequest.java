package com.bms.dto.draft;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public class DraftCreateRequest {
    @NotEmpty
    @Valid
    private List<Item> items;
    private Long customerId;
    private String notes;

    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public static class Item {
        @NotNull private Long productId;
        @NotNull @Positive private Integer quantity;
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
    }
}