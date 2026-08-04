package com.bms.dto.response;

import java.util.List;

public class CustomerRetentionDto {
    private Integer activeLastMonth;
    private Integer activeThisMonth;
    private Integer returningCount;
    private Integer lapsedCount;
    private List<LapsedCustomer> lapsedCustomers;

    public static class LapsedCustomer {
        private Long customerId;
        private String customerName;
        private String phone;
        private java.time.LocalDateTime lastPurchaseDate;
        private java.math.BigDecimal totalHistoricalSpend;

        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }

        public String getCustomerName() { return customerName; }
        public void setCustomerName(String customerName) { this.customerName = customerName; }

        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }

        public java.time.LocalDateTime getLastPurchaseDate() { return lastPurchaseDate; }
        public void setLastPurchaseDate(java.time.LocalDateTime lastPurchaseDate) { this.lastPurchaseDate = lastPurchaseDate; }

        public java.math.BigDecimal getTotalHistoricalSpend() { return totalHistoricalSpend; }
        public void setTotalHistoricalSpend(java.math.BigDecimal totalHistoricalSpend) { this.totalHistoricalSpend = totalHistoricalSpend; }
    }

    public Integer getActiveLastMonth() { return activeLastMonth; }
    public void setActiveLastMonth(Integer activeLastMonth) { this.activeLastMonth = activeLastMonth; }

    public Integer getActiveThisMonth() { return activeThisMonth; }
    public void setActiveThisMonth(Integer activeThisMonth) { this.activeThisMonth = activeThisMonth; }

    public Integer getReturningCount() { return returningCount; }
    public void setReturningCount(Integer returningCount) { this.returningCount = returningCount; }

    public Integer getLapsedCount() { return lapsedCount; }
    public void setLapsedCount(Integer lapsedCount) { this.lapsedCount = lapsedCount; }

    public List<LapsedCustomer> getLapsedCustomers() { return lapsedCustomers; }
    public void setLapsedCustomers(List<LapsedCustomer> lapsedCustomers) { this.lapsedCustomers = lapsedCustomers; }
}
