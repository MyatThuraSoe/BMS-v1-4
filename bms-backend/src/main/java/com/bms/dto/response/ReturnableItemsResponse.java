package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Per-line returnability snapshot for a sale: what was sold, what has already
 * come back across all prior returns, and what can still be returned, priced
 * from the original sale (tax-inclusive) so the client never computes money.
 */
public class ReturnableItemsResponse {
    private Long saleId;
    private String invoiceNumber;
    private LocalDateTime saleDate;
    private Boolean voided;
    private List<ReturnableItem> items;

    public Long getSaleId() { return saleId; }
    public void setSaleId(Long saleId) { this.saleId = saleId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public LocalDateTime getSaleDate() { return saleDate; }
    public void setSaleDate(LocalDateTime saleDate) { this.saleDate = saleDate; }
    public Boolean getVoided() { return voided; }
    public void setVoided(Boolean voided) { this.voided = voided; }
    public List<ReturnableItem> getItems() { return items; }
    public void setItems(List<ReturnableItem> items) { this.items = items; }

    public static class ReturnableItem {
        private Long saleItemId;
        private Long productId;
        private String productName;
        private Integer quantitySold;
        private Integer quantityAlreadyReturned;
        private Integer quantityReturnable;
        private BigDecimal unitPrice;
        private BigDecimal unitRefundAmount;

        public Long getSaleItemId() { return saleItemId; }
        public void setSaleItemId(Long saleItemId) { this.saleItemId = saleItemId; }
        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public Integer getQuantitySold() { return quantitySold; }
        public void setQuantitySold(Integer quantitySold) { this.quantitySold = quantitySold; }
        public Integer getQuantityAlreadyReturned() { return quantityAlreadyReturned; }
        public void setQuantityAlreadyReturned(Integer quantityAlreadyReturned) { this.quantityAlreadyReturned = quantityAlreadyReturned; }
        public Integer getQuantityReturnable() { return quantityReturnable; }
        public void setQuantityReturnable(Integer quantityReturnable) { this.quantityReturnable = quantityReturnable; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
        public BigDecimal getUnitRefundAmount() { return unitRefundAmount; }
        public void setUnitRefundAmount(BigDecimal unitRefundAmount) { this.unitRefundAmount = unitRefundAmount; }
    }
}
