package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ProductTopCustomerDto {
    private Long customerId;
    private String customerName;
    private String phone;
    private Long totalQuantityBought;
    private BigDecimal totalSpent;
    private LocalDateTime lastPurchaseDate;

    public ProductTopCustomerDto() {}

    public ProductTopCustomerDto(Long customerId, String customerName, String phone,
                                  Long totalQuantityBought, BigDecimal totalSpent,
                                  LocalDateTime lastPurchaseDate) {
        this.customerId = customerId;
        this.customerName = customerName;
        this.phone = phone;
        this.totalQuantityBought = totalQuantityBought;
        this.totalSpent = totalSpent;
        this.lastPurchaseDate = lastPurchaseDate;
    }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Long getTotalQuantityBought() { return totalQuantityBought; }
    public void setTotalQuantityBought(Long totalQuantityBought) { this.totalQuantityBought = totalQuantityBought; }

    public BigDecimal getTotalSpent() { return totalSpent; }
    public void setTotalSpent(BigDecimal totalSpent) { this.totalSpent = totalSpent; }

    public LocalDateTime getLastPurchaseDate() { return lastPurchaseDate; }
    public void setLastPurchaseDate(LocalDateTime lastPurchaseDate) { this.lastPurchaseDate = lastPurchaseDate; }
}
