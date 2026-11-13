package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * P8 req 4: price-history rows carry their type and full audit context so the caller
 * can filter by priceType and see source, reason, originating document, the stock
 * position at the change and the inventory value delta.
 */
public class PriceHistoryDto {
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private LocalDateTime changedAt;
    private String changedByUsername;
    private String priceType;
    private String source;
    private String reason;
    private Long supplierId;
    private Long purchaseId;
    private Integer stockQtyAtChange;
    private BigDecimal inventoryValueDelta;

    public PriceHistoryDto() {}

    public BigDecimal getOldPrice() { return oldPrice; }
    public void setOldPrice(BigDecimal oldPrice) { this.oldPrice = oldPrice; }

    public BigDecimal getNewPrice() { return newPrice; }
    public void setNewPrice(BigDecimal newPrice) { this.newPrice = newPrice; }

    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }

    public String getChangedByUsername() { return changedByUsername; }
    public void setChangedByUsername(String changedByUsername) { this.changedByUsername = changedByUsername; }

    public String getPriceType() { return priceType; }
    public void setPriceType(String priceType) { this.priceType = priceType; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Long getSupplierId() { return supplierId; }
    public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }

    public Long getPurchaseId() { return purchaseId; }
    public void setPurchaseId(Long purchaseId) { this.purchaseId = purchaseId; }

    public Integer getStockQtyAtChange() { return stockQtyAtChange; }
    public void setStockQtyAtChange(Integer stockQtyAtChange) { this.stockQtyAtChange = stockQtyAtChange; }

    public BigDecimal getInventoryValueDelta() { return inventoryValueDelta; }
    public void setInventoryValueDelta(BigDecimal inventoryValueDelta) { this.inventoryValueDelta = inventoryValueDelta; }
}