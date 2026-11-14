package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only AR reconciliation snapshot (O1): per customer, the outstanding
 * debt derived from open credit invoices (the same source as sumOutstandingAr)
 * versus the stored Customer.currentBalance, plus any credit held from
 * overpayments. A mismatch means the stored balance drifted and needs review;
 * nothing here mutates data.
 */
public class ArReconciliationResponse {
    private LocalDateTime checkedAt;
    private BigDecimal totalExpectedOutstanding;
    private BigDecimal totalStoredBalance;
    private BigDecimal totalCreditBalance;
    private int mismatchCount;
    private List<CustomerReconciliation> customers;

    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
    public BigDecimal getTotalExpectedOutstanding() { return totalExpectedOutstanding; }
    public void setTotalExpectedOutstanding(BigDecimal totalExpectedOutstanding) { this.totalExpectedOutstanding = totalExpectedOutstanding; }
    public BigDecimal getTotalStoredBalance() { return totalStoredBalance; }
    public void setTotalStoredBalance(BigDecimal totalStoredBalance) { this.totalStoredBalance = totalStoredBalance; }
    public BigDecimal getTotalCreditBalance() { return totalCreditBalance; }
    public void setTotalCreditBalance(BigDecimal totalCreditBalance) { this.totalCreditBalance = totalCreditBalance; }
    public int getMismatchCount() { return mismatchCount; }
    public void setMismatchCount(int mismatchCount) { this.mismatchCount = mismatchCount; }
    public List<CustomerReconciliation> getCustomers() { return customers; }
    public void setCustomers(List<CustomerReconciliation> customers) { this.customers = customers; }

    public static class CustomerReconciliation {
        private Long customerId;
        private String customerCode;
        private String customerName;
        private BigDecimal expectedOutstanding;
        private BigDecimal currentBalance;
        private BigDecimal creditBalance;
        /** Stored currentBalance minus expected outstanding; zero when in sync. */
        private BigDecimal difference;
        private boolean mismatch;

        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
        public String getCustomerCode() { return customerCode; }
        public void setCustomerCode(String customerCode) { this.customerCode = customerCode; }
        public String getCustomerName() { return customerName; }
        public void setCustomerName(String customerName) { this.customerName = customerName; }
        public BigDecimal getExpectedOutstanding() { return expectedOutstanding; }
        public void setExpectedOutstanding(BigDecimal expectedOutstanding) { this.expectedOutstanding = expectedOutstanding; }
        public BigDecimal getCurrentBalance() { return currentBalance; }
        public void setCurrentBalance(BigDecimal currentBalance) { this.currentBalance = currentBalance; }
        public BigDecimal getCreditBalance() { return creditBalance; }
        public void setCreditBalance(BigDecimal creditBalance) { this.creditBalance = creditBalance; }
        public BigDecimal getDifference() { return difference; }
        public void setDifference(BigDecimal difference) { this.difference = difference; }
        public boolean isMismatch() { return mismatch; }
        public void setMismatch(boolean mismatch) { this.mismatch = mismatch; }
    }
}