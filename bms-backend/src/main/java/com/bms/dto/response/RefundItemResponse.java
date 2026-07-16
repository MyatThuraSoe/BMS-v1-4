package com.bms.dto.response;

import java.math.BigDecimal;

public class RefundItemResponse {
    private Long id;
    private Long saleItemId;
    private Long productId;
    private String productName;
    private Integer quantityRefunded;
    private BigDecimal refundAmount;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getSaleItemId() { return saleItemId; }
    public void setSaleItemId(Long saleItemId) { this.saleItemId = saleItemId; }
    
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    
    public Integer getQuantityRefunded() { return quantityRefunded; }
    public void setQuantityRefunded(Integer quantityRefunded) { this.quantityRefunded = quantityRefunded; }
    
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
}
