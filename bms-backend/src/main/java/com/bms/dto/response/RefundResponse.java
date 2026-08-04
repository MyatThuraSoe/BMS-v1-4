package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class RefundResponse {
    private Long id;
    private Long saleId;
    private String invoiceNumber;
    private Long refundedBy;
    private String refundedByUsername;
    private LocalDateTime refundDate;
    private String reason;
    private BigDecimal totalRefundAmount;
    private List<RefundItemResponse> items;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSaleId() { return saleId; }
    public void setSaleId(Long saleId) { this.saleId = saleId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public Long getRefundedBy() { return refundedBy; }
    public void setRefundedBy(Long refundedBy) { this.refundedBy = refundedBy; }
    public String getRefundedByUsername() { return refundedByUsername; }
    public void setRefundedByUsername(String refundedByUsername) { this.refundedByUsername = refundedByUsername; }
    public LocalDateTime getRefundDate() { return refundDate; }
    public void setRefundDate(LocalDateTime refundDate) { this.refundDate = refundDate; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public BigDecimal getTotalRefundAmount() { return totalRefundAmount; }
    public void setTotalRefundAmount(BigDecimal totalRefundAmount) { this.totalRefundAmount = totalRefundAmount; }
    public List<RefundItemResponse> getItems() { return items; }
    public void setItems(List<RefundItemResponse> items) { this.items = items; }

    public static class RefundItemResponse {
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
}
