package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class CustomerLtvDto {
    private Long customerId;
    private String customerName;
    private String phone;
    private BigDecimal totalSpent;
    private Integer visitCount;
    private BigDecimal averageBasketSize;
    private LocalDateTime firstPurchaseDate;
    private LocalDateTime lastPurchaseDate;

    public CustomerLtvDto() {}

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public BigDecimal getTotalSpent() { return totalSpent; }
    public void setTotalSpent(BigDecimal totalSpent) { this.totalSpent = totalSpent; }

    public Integer getVisitCount() { return visitCount; }
    public void setVisitCount(Integer visitCount) { this.visitCount = visitCount; }

    public BigDecimal getAverageBasketSize() { return averageBasketSize; }
    public void setAverageBasketSize(BigDecimal averageBasketSize) { this.averageBasketSize = averageBasketSize; }

    public LocalDateTime getFirstPurchaseDate() { return firstPurchaseDate; }
    public void setFirstPurchaseDate(LocalDateTime firstPurchaseDate) { this.firstPurchaseDate = firstPurchaseDate; }

    public LocalDateTime getLastPurchaseDate() { return lastPurchaseDate; }
    public void setLastPurchaseDate(LocalDateTime lastPurchaseDate) { this.lastPurchaseDate = lastPurchaseDate; }
}
