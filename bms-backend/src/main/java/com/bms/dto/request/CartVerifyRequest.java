package com.bms.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

public class CartVerifyRequest {
    @NotEmpty(message = "Cart items are required")
    private List<CartVerifyItem> items;

    public List<CartVerifyItem> getItems() { return items; }
    public void setItems(List<CartVerifyItem> items) { this.items = items; }

    public static class CartVerifyItem {
        @NotNull
        private Long productId;

        @NotNull @Positive
        private Integer quantity;

        // What the frontend currently has cached — used only to detect drift, never trusted for the total
        @NotNull
        private BigDecimal expectedUnitPrice;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public BigDecimal getExpectedUnitPrice() { return expectedUnitPrice; }
        public void setExpectedUnitPrice(BigDecimal expectedUnitPrice) { this.expectedUnitPrice = expectedUnitPrice; }
    }
}