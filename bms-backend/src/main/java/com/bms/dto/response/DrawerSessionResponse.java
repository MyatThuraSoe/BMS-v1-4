package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class DrawerSessionResponse {
    private Long id;
    private Long drawerId;
    private String drawerName;
    private String drawerCode;
    private Long openedById;
    private String openedByName;
    private Long closedById;
    private String closedByName;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private String status;
    private BigDecimal openingFloat;
    private BigDecimal expectedCash;
    private BigDecimal countedCash;
    private BigDecimal variance;
    private String closingNote;
    private Boolean blindClose;
    private Boolean expectedHidden;
    private Boolean forceClosed;
    private String forceCloseReason;

    private BigDecimal cashSales;
    private BigDecimal cashRefunds;
    private BigDecimal safeDrops;
    private BigDecimal payIns;
    private BigDecimal payOuts;
    private BigDecimal adjustments;
    private long movementCount;

    private List<DrawerMovementResponse> movements;
    private List<EmployeeCashContribution> contributingEmployees;
    private Map<String, BigDecimal> movementBreakdown;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDrawerId() { return drawerId; }
    public void setDrawerId(Long drawerId) { this.drawerId = drawerId; }

    public String getDrawerName() { return drawerName; }
    public void setDrawerName(String drawerName) { this.drawerName = drawerName; }

    public String getDrawerCode() { return drawerCode; }
    public void setDrawerCode(String drawerCode) { this.drawerCode = drawerCode; }

    public Long getOpenedById() { return openedById; }
    public void setOpenedById(Long openedById) { this.openedById = openedById; }

    public String getOpenedByName() { return openedByName; }
    public void setOpenedByName(String openedByName) { this.openedByName = openedByName; }

    public Long getClosedById() { return closedById; }
    public void setClosedById(Long closedById) { this.closedById = closedById; }

    public String getClosedByName() { return closedByName; }
    public void setClosedByName(String closedByName) { this.closedByName = closedByName; }

    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }

    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getOpeningFloat() { return openingFloat; }
    public void setOpeningFloat(BigDecimal openingFloat) { this.openingFloat = openingFloat; }

    public BigDecimal getExpectedCash() { return expectedCash; }
    public void setExpectedCash(BigDecimal expectedCash) { this.expectedCash = expectedCash; }

    public BigDecimal getCountedCash() { return countedCash; }
    public void setCountedCash(BigDecimal countedCash) { this.countedCash = countedCash; }

    public BigDecimal getVariance() { return variance; }
    public void setVariance(BigDecimal variance) { this.variance = variance; }

    public String getClosingNote() { return closingNote; }
    public void setClosingNote(String closingNote) { this.closingNote = closingNote; }

    public Boolean getBlindClose() { return blindClose; }
    public void setBlindClose(Boolean blindClose) { this.blindClose = blindClose; }

    public Boolean getExpectedHidden() { return expectedHidden; }
    public void setExpectedHidden(Boolean expectedHidden) { this.expectedHidden = expectedHidden; }

    public Boolean getForceClosed() { return forceClosed; }
    public void setForceClosed(Boolean forceClosed) { this.forceClosed = forceClosed; }

    public String getForceCloseReason() { return forceCloseReason; }
    public void setForceCloseReason(String forceCloseReason) { this.forceCloseReason = forceCloseReason; }

    public BigDecimal getCashSales() { return cashSales; }
    public void setCashSales(BigDecimal cashSales) { this.cashSales = cashSales; }

    public BigDecimal getCashRefunds() { return cashRefunds; }
    public void setCashRefunds(BigDecimal cashRefunds) { this.cashRefunds = cashRefunds; }

    public BigDecimal getSafeDrops() { return safeDrops; }
    public void setSafeDrops(BigDecimal safeDrops) { this.safeDrops = safeDrops; }

    public BigDecimal getPayIns() { return payIns; }
    public void setPayIns(BigDecimal payIns) { this.payIns = payIns; }

    public BigDecimal getPayOuts() { return payOuts; }
    public void setPayOuts(BigDecimal payOuts) { this.payOuts = payOuts; }

    public BigDecimal getAdjustments() { return adjustments; }
    public void setAdjustments(BigDecimal adjustments) { this.adjustments = adjustments; }

    public long getMovementCount() { return movementCount; }
    public void setMovementCount(long movementCount) { this.movementCount = movementCount; }

    public List<DrawerMovementResponse> getMovements() { return movements; }
    public void setMovements(List<DrawerMovementResponse> movements) { this.movements = movements; }

    public List<EmployeeCashContribution> getContributingEmployees() { return contributingEmployees; }
    public void setContributingEmployees(List<EmployeeCashContribution> contributingEmployees) { this.contributingEmployees = contributingEmployees; }

    public Map<String, BigDecimal> getMovementBreakdown() { return movementBreakdown; }
    public void setMovementBreakdown(Map<String, BigDecimal> movementBreakdown) { this.movementBreakdown = movementBreakdown; }

    public static class EmployeeCashContribution {
        private Long employeeShiftId;
        private Long userId;
        private String employeeName;
        private BigDecimal cashSales;
        private BigDecimal cashRefunds;

        public Long getEmployeeShiftId() { return employeeShiftId; }
        public void setEmployeeShiftId(Long employeeShiftId) { this.employeeShiftId = employeeShiftId; }

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }

        public String getEmployeeName() { return employeeName; }
        public void setEmployeeName(String employeeName) { this.employeeName = employeeName; }

        public BigDecimal getCashSales() { return cashSales; }
        public void setCashSales(BigDecimal cashSales) { this.cashSales = cashSales; }

        public BigDecimal getCashRefunds() { return cashRefunds; }
        public void setCashRefunds(BigDecimal cashRefunds) { this.cashRefunds = cashRefunds; }
    }
}
