package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class RefundResponse {
    private Long id;
    private Long saleId;
    private String invoiceNumber;
    private Long refundedById;
    private String refundedByName;
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
    
    public Long getRefundedById() { return refundedById; }
    public void setRefundedById(Long refundedById) { this.refundedById = refundedById; }
    
    public String getRefundedByName() { return refundedByName; }
    public void setRefundedByName(String refundedByName) { this.refundedByName = refundedByName; }
    
    public LocalDateTime getRefundDate() { return refundDate; }
    public void setRefundDate(LocalDateTime refundDate) { this.refundDate = refundDate; }
    
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    
    public BigDecimal getTotalRefundAmount() { return totalRefundAmount; }
    public void setTotalRefundAmount(BigDecimal totalRefundAmount) { this.totalRefundAmount = totalRefundAmount; }
    
    public List<RefundItemResponse> getItems() { return items; }
    public void setItems(List<RefundItemResponse> items) { this.items = items; }
}
