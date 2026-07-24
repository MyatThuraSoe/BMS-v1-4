package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PriceHistoryDto {
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private LocalDateTime changedAt;
    private String changedByUsername;

    public PriceHistoryDto() {}

    public PriceHistoryDto(BigDecimal oldPrice, BigDecimal newPrice,
                           LocalDateTime changedAt, String changedByUsername) {
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
        this.changedAt = changedAt;
        this.changedByUsername = changedByUsername;
    }

    public BigDecimal getOldPrice() { return oldPrice; }
    public void setOldPrice(BigDecimal oldPrice) { this.oldPrice = oldPrice; }

    public BigDecimal getNewPrice() { return newPrice; }
    public void setNewPrice(BigDecimal newPrice) { this.newPrice = newPrice; }

    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }

    public String getChangedByUsername() { return changedByUsername; }
    public void setChangedByUsername(String changedByUsername) { this.changedByUsername = changedByUsername; }
}
