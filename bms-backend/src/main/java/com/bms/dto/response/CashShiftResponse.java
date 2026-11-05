package com.bms.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class CashShiftResponse {
    private Long id;
    private Long cashierId;
    private String cashierName;
    private String shiftType;
    private BigDecimal openingAmount;
    private java.time.LocalDateTime openingTime;
    private BigDecimal closingAmount;
    private java.time.LocalDateTime closingTime;
    private BigDecimal expectedAmount;
    private BigDecimal variance;
    private String status;
    private String notes;
    private BigDecimal cashSalesTotal;
    private BigDecimal returnsTotal;
    /** Employee cash sales + refunds for this shift (not drawer-wide). */
    private BigDecimal drawerCashSalesTotal;
    private BigDecimal nonCashSalesTotal;
    private BigDecimal grossSales;
    private BigDecimal discountTotal;
    private BigDecimal netSales;
    private long transactionCount;
    private long voidedCount;
    private Map<String, BigDecimal> paymentBreakdown;

    private Long drawerSessionId;
    private Long drawerId;
    private String drawerName;
    private String drawerCode;
    private String drawerSessionStatus;
    private Long drawerOwnerId;
    private String drawerOwnerName;
    private Boolean cashAllowed;
    private Boolean canJoinDrawer;
    private Boolean blindClose;
    private Boolean expectedCashHidden;
    private Boolean forceClosed;
    private String forceCloseReason;

    /** Largest absolute variance still treated as ordinary counting noise. */
    private BigDecimal varianceTolerance;
    /** True when the variance is outside that tolerance. */
    private boolean varianceExceedsTolerance;
    private List<SaleReference> sales;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCashierId() { return cashierId; }
    public void setCashierId(Long cashierId) { this.cashierId = cashierId; }

    public String getCashierName() { return cashierName; }
    public void setCashierName(String cashierName) { this.cashierName = cashierName; }

    public String getShiftType() { return shiftType; }
    public void setShiftType(String shiftType) { this.shiftType = shiftType; }

    public BigDecimal getOpeningAmount() { return openingAmount; }
    public void setOpeningAmount(BigDecimal openingAmount) { this.openingAmount = openingAmount; }

    public java.time.LocalDateTime getOpeningTime() { return openingTime; }
    public void setOpeningTime(java.time.LocalDateTime openingTime) { this.openingTime = openingTime; }

    public BigDecimal getClosingAmount() { return closingAmount; }
    public void setClosingAmount(BigDecimal closingAmount) { this.closingAmount = closingAmount; }

    public java.time.LocalDateTime getClosingTime() { return closingTime; }
    public void setClosingTime(java.time.LocalDateTime closingTime) { this.closingTime = closingTime; }

    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(BigDecimal expectedAmount) { this.expectedAmount = expectedAmount; }

    public BigDecimal getVariance() { return variance; }
    public void setVariance(BigDecimal variance) { this.variance = variance; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public BigDecimal getCashSalesTotal() { return cashSalesTotal; }
    public void setCashSalesTotal(BigDecimal cashSalesTotal) { this.cashSalesTotal = cashSalesTotal; }

    public BigDecimal getReturnsTotal() { return returnsTotal; }
    public void setReturnsTotal(BigDecimal returnsTotal) { this.returnsTotal = returnsTotal; }

    public BigDecimal getDrawerCashSalesTotal() { return drawerCashSalesTotal; }
    public void setDrawerCashSalesTotal(BigDecimal drawerCashSalesTotal) { this.drawerCashSalesTotal = drawerCashSalesTotal; }

    public BigDecimal getNonCashSalesTotal() { return nonCashSalesTotal; }
    public void setNonCashSalesTotal(BigDecimal nonCashSalesTotal) { this.nonCashSalesTotal = nonCashSalesTotal; }

    public BigDecimal getGrossSales() { return grossSales; }
    public void setGrossSales(BigDecimal grossSales) { this.grossSales = grossSales; }

    public BigDecimal getDiscountTotal() { return discountTotal; }
    public void setDiscountTotal(BigDecimal discountTotal) { this.discountTotal = discountTotal; }

    public BigDecimal getNetSales() { return netSales; }
    public void setNetSales(BigDecimal netSales) { this.netSales = netSales; }

    public long getTransactionCount() { return transactionCount; }
    public void setTransactionCount(long transactionCount) { this.transactionCount = transactionCount; }

    public long getVoidedCount() { return voidedCount; }
    public void setVoidedCount(long voidedCount) { this.voidedCount = voidedCount; }

    public Map<String, BigDecimal> getPaymentBreakdown() { return paymentBreakdown; }
    public void setPaymentBreakdown(Map<String, BigDecimal> paymentBreakdown) { this.paymentBreakdown = paymentBreakdown; }

    public Long getDrawerSessionId() { return drawerSessionId; }
    public void setDrawerSessionId(Long drawerSessionId) { this.drawerSessionId = drawerSessionId; }

    public Long getDrawerId() { return drawerId; }
    public void setDrawerId(Long drawerId) { this.drawerId = drawerId; }

    public String getDrawerName() { return drawerName; }
    public void setDrawerName(String drawerName) { this.drawerName = drawerName; }

    public String getDrawerCode() { return drawerCode; }
    public void setDrawerCode(String drawerCode) { this.drawerCode = drawerCode; }

    public String getDrawerSessionStatus() { return drawerSessionStatus; }
    public void setDrawerSessionStatus(String drawerSessionStatus) { this.drawerSessionStatus = drawerSessionStatus; }

    public Long getDrawerOwnerId() { return drawerOwnerId; }
    public void setDrawerOwnerId(Long drawerOwnerId) { this.drawerOwnerId = drawerOwnerId; }

    public String getDrawerOwnerName() { return drawerOwnerName; }
    public void setDrawerOwnerName(String drawerOwnerName) { this.drawerOwnerName = drawerOwnerName; }

    public Boolean getCashAllowed() { return cashAllowed; }
    public void setCashAllowed(Boolean cashAllowed) { this.cashAllowed = cashAllowed; }

    public Boolean getCanJoinDrawer() { return canJoinDrawer; }
    public void setCanJoinDrawer(Boolean canJoinDrawer) { this.canJoinDrawer = canJoinDrawer; }

    public Boolean getBlindClose() { return blindClose; }
    public void setBlindClose(Boolean blindClose) { this.blindClose = blindClose; }

    public Boolean getExpectedCashHidden() { return expectedCashHidden; }
    public void setExpectedCashHidden(Boolean expectedCashHidden) { this.expectedCashHidden = expectedCashHidden; }

    public Boolean getForceClosed() { return forceClosed; }
    public void setForceClosed(Boolean forceClosed) { this.forceClosed = forceClosed; }

    public String getForceCloseReason() { return forceCloseReason; }
    public void setForceCloseReason(String forceCloseReason) { this.forceCloseReason = forceCloseReason; }

    public BigDecimal getVarianceTolerance() { return varianceTolerance; }
    public void setVarianceTolerance(BigDecimal varianceTolerance) { this.varianceTolerance = varianceTolerance; }

    public boolean isVarianceExceedsTolerance() { return varianceExceedsTolerance; }
    public void setVarianceExceedsTolerance(boolean varianceExceedsTolerance) { this.varianceExceedsTolerance = varianceExceedsTolerance; }

    public List<SaleReference> getSales() { return sales; }
    public void setSales(List<SaleReference> sales) { this.sales = sales; }

    public static class SaleReference {
        private Long id;
        private String invoiceNumber;
        private BigDecimal totalAmount;
        private java.time.LocalDateTime saleDate;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getInvoiceNumber() { return invoiceNumber; }
        public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

        public BigDecimal getTotalAmount() { return totalAmount; }
        public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

        public java.time.LocalDateTime getSaleDate() { return saleDate; }
        public void setSaleDate(java.time.LocalDateTime saleDate) { this.saleDate = saleDate; }
    }
}
