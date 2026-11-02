package com.bms.service;

import java.math.BigDecimal;

/** Result of applying the best price list / promotion to one cart line. */
public class PricingQuote {
    private final BigDecimal originalUnitPrice;
    private final BigDecimal unitPrice;
    private final BigDecimal lineDiscount;
    private final Long promotionId;
    private final String promotionName;
    private final Long priceListId;
    private final String priceListName;
    private final String source;

    public PricingQuote(BigDecimal originalUnitPrice, BigDecimal unitPrice,
                        BigDecimal lineDiscount, Long promotionId, String promotionName,
                        Long priceListId, String priceListName, String source) {
        this.originalUnitPrice = originalUnitPrice;
        this.unitPrice = unitPrice;
        this.lineDiscount = lineDiscount;
        this.promotionId = promotionId;
        this.promotionName = promotionName;
        this.priceListId = priceListId;
        this.priceListName = priceListName;
        this.source = source;
    }

    public BigDecimal getOriginalUnitPrice() { return originalUnitPrice; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getLineDiscount() { return lineDiscount; }
    public Long getPromotionId() { return promotionId; }
    public String getPromotionName() { return promotionName; }
    public Long getPriceListId() { return priceListId; }
    public String getPriceListName() { return priceListName; }
    public String getSource() { return source; }
}
