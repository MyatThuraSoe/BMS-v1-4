package com.bms.dto.response;

public class BasketAffinityDto {
    private Long productId;
    private String productName;
    private Long coOccurrenceCount;

    public BasketAffinityDto() {}

    public BasketAffinityDto(Long productId, String productName, Long coOccurrenceCount) {
        this.productId = productId;
        this.productName = productName;
        this.coOccurrenceCount = coOccurrenceCount;
    }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Long getCoOccurrenceCount() { return coOccurrenceCount; }
    public void setCoOccurrenceCount(Long coOccurrenceCount) { this.coOccurrenceCount = coOccurrenceCount; }
}
