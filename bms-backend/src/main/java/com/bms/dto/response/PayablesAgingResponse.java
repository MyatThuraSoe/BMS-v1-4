package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * O3: payables aging grouped by supplier, bucketed by the age of the purchase
 * date as of {@link #asOfDate}. All amounts are outstanding payables converted
 * to the shop's base currency (invoice-currency outstanding x exchange rate).
 */
public class PayablesAgingResponse {

    private LocalDate asOfDate;
    private List<SupplierAging> suppliers = new ArrayList<>();
    private SupplierAging totals = new SupplierAging();

    public LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }
    public List<SupplierAging> getSuppliers() { return suppliers; }
    public void setSuppliers(List<SupplierAging> suppliers) { this.suppliers = suppliers; }
    public SupplierAging getTotals() { return totals; }
    public void setTotals(SupplierAging totals) { this.totals = totals; }

    public static class SupplierAging {
        private Long supplierId;
        private String supplierName;
        private BigDecimal current = BigDecimal.ZERO;
        private BigDecimal days1To30 = BigDecimal.ZERO;
        private BigDecimal days31To60 = BigDecimal.ZERO;
        private BigDecimal days61To90 = BigDecimal.ZERO;
        private BigDecimal days90Plus = BigDecimal.ZERO;
        private BigDecimal totalOutstanding = BigDecimal.ZERO;

        public Long getSupplierId() { return supplierId; }
        public void setSupplierId(Long supplierId) { this.supplierId = supplierId; }
        public String getSupplierName() { return supplierName; }
        public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
        public BigDecimal getCurrent() { return current; }
        public void setCurrent(BigDecimal current) { this.current = current; }
        public BigDecimal getDays1To30() { return days1To30; }
        public void setDays1To30(BigDecimal days1To30) { this.days1To30 = days1To30; }
        public BigDecimal getDays31To60() { return days31To60; }
        public void setDays31To60(BigDecimal days31To60) { this.days31To60 = days31To60; }
        public BigDecimal getDays61To90() { return days61To90; }
        public void setDays61To90(BigDecimal days61To90) { this.days61To90 = days61To90; }
        public BigDecimal getDays90Plus() { return days90Plus; }
        public void setDays90Plus(BigDecimal days90Plus) { this.days90Plus = days90Plus; }
        public BigDecimal getTotalOutstanding() { return totalOutstanding; }
        public void setTotalOutstanding(BigDecimal totalOutstanding) { this.totalOutstanding = totalOutstanding; }
    }
}