package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class CashShiftResponse {
    private Long id;
    private Long cashierId;
    private String cashierName;
    private BigDecimal openingAmount;
    private LocalDateTime openingTime;
    private BigDecimal closingAmount;
    private LocalDateTime closingTime;
    private BigDecimal expectedAmount;
    private BigDecimal variance;
    private String status;
    private String notes;
    private List<SaleResponse> sales;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCashierId() { return cashierId; }
    public void setCashierId(Long cashierId) { this.cashierId = cashierId; }

    public String getCashierName() { return cashierName; }
    public void setCashierName(String cashierName) { this.cashierName = cashierName; }

    public BigDecimal getOpeningAmount() { return openingAmount; }
    public void setOpeningAmount(BigDecimal openingAmount) { this.openingAmount = openingAmount; }

    public LocalDateTime getOpeningTime() { return openingTime; }
    public void setOpeningTime(LocalDateTime openingTime) { this.openingTime = openingTime; }

    public BigDecimal getClosingAmount() { return closingAmount; }
    public void setClosingAmount(BigDecimal closingAmount) { this.closingAmount = closingAmount; }

    public LocalDateTime getClosingTime() { return closingTime; }
    public void setClosingTime(LocalDateTime closingTime) { this.closingTime = closingTime; }

    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(BigDecimal expectedAmount) { this.expectedAmount = expectedAmount; }

    public BigDecimal getVariance() { return variance; }
    public void setVariance(BigDecimal variance) { this.variance = variance; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public List<SaleResponse> getSales() { return sales; }
    public void setSales(List<SaleResponse> sales) { this.sales = sales; }
}
