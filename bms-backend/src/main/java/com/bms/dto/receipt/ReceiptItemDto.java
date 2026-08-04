package com.bms.dto.receipt;

import java.math.BigDecimal;

public class ReceiptItemDto {

    private Long productId;
    private Long saleItemId;
    private String productName;
    private String sku;
    private Integer quantity;
    private Integer quantityRefunded;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;


    // No-Args Constructor
    public ReceiptItemDto() {
    }


    // All-Args Constructor
    public ReceiptItemDto(
            Long productId,
            Long saleItemId,
            String productName,
            String sku,
            Integer quantity,
            Integer quantityRefunded,
            BigDecimal unitPrice,
            BigDecimal subtotal
    ) {
        this.productId = productId;
        this.saleItemId = saleItemId;
        this.productName = productName;
        this.sku = sku;
        this.quantity = quantity;
        this.quantityRefunded = quantityRefunded;
        this.unitPrice = unitPrice;
        this.subtotal = subtotal;
    }


    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getSaleItemId() {
        return saleItemId;
    }

    public void setSaleItemId(Long saleItemId) {
        this.saleItemId = saleItemId;
    }


    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }


    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }


    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Integer getQuantityRefunded() {
        return quantityRefunded;
    }

    public void setQuantityRefunded(Integer quantityRefunded) {
        this.quantityRefunded = quantityRefunded;
    }


    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }


    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }
}
