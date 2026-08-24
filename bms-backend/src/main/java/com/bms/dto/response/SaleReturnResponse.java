package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class SaleReturnResponse {
    private Long id;
    private Long saleId;
    private String invoiceNumber;
    private Long returnedBy;
    private String returnedByUsername;
    private LocalDateTime returnDate;
    private String reason;
    private BigDecimal totalReturnAmount;
    private List<SaleReturnItemResponse> items;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSaleId() { return saleId; }
    public void setSaleId(Long saleId) { this.saleId = saleId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public Long getReturnedBy() { return returnedBy; }
    public void setReturnedBy(Long returnedBy) { this.returnedBy = returnedBy; }
    public String getReturnedByUsername() { return returnedByUsername; }
    public void setReturnedByUsername(String returnedByUsername) { this.returnedByUsername = returnedByUsername; }
    public LocalDateTime getReturnDate() { return returnDate; }
    public void setReturnDate(LocalDateTime returnDate) { this.returnDate = returnDate; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public BigDecimal getTotalReturnAmount() { return totalReturnAmount; }
    public void setTotalReturnAmount(BigDecimal totalReturnAmount) { this.totalReturnAmount = totalReturnAmount; }
    public List<SaleReturnItemResponse> getItems() { return items; }
    public void setItems(List<SaleReturnItemResponse> items) { this.items = items; }

    public static class SaleReturnItemResponse {
        private Long id;
        private Long saleItemId;
        private Long productId;
        private String productName;
        private Integer quantityReturned;
        private BigDecimal returnAmount;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getSaleItemId() { return saleItemId; }
        public void setSaleItemId(Long saleItemId) { this.saleItemId = saleItemId; }
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public Integer getQuantityReturned() { return quantityReturned; }
        public void setQuantityReturned(Integer quantityReturned) { this.quantityReturned = quantityReturned; }
        public BigDecimal getReturnAmount() { return returnAmount; }
        public void setReturnAmount(BigDecimal returnAmount) { this.returnAmount = returnAmount; }
    }
}
