package com.bms.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Everything the Start Shift screen needs, even when the caller has no shift yet. */
public class ShiftStartContextResponse {
    private Long drawerId;
    private String drawerName;
    private String drawerCode;
    private String drawerStatus;
    private Long openDrawerSessionId;
    private Long openedById;
    private String openedByName;
    private LocalDateTime openedAt;
    private BigDecimal openingFloat;
    private boolean allowSharedDrawer;
    private boolean managerApprovalRequired;
    private boolean managerOrAdmin;
    private boolean canOpenCashDrawer;
    private boolean canOpenNonCash;
    private boolean canJoinExisting;
    private String message;

    public Long getDrawerId() { return drawerId; }
    public void setDrawerId(Long drawerId) { this.drawerId = drawerId; }

    public String getDrawerName() { return drawerName; }
    public void setDrawerName(String drawerName) { this.drawerName = drawerName; }

    public String getDrawerCode() { return drawerCode; }
    public void setDrawerCode(String drawerCode) { this.drawerCode = drawerCode; }

    public String getDrawerStatus() { return drawerStatus; }
    public void setDrawerStatus(String drawerStatus) { this.drawerStatus = drawerStatus; }

    public Long getOpenDrawerSessionId() { return openDrawerSessionId; }
    public void setOpenDrawerSessionId(Long openDrawerSessionId) { this.openDrawerSessionId = openDrawerSessionId; }

    public Long getOpenedById() { return openedById; }
    public void setOpenedById(Long openedById) { this.openedById = openedById; }

    public String getOpenedByName() { return openedByName; }
    public void setOpenedByName(String openedByName) { this.openedByName = openedByName; }

    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }

    public BigDecimal getOpeningFloat() { return openingFloat; }
    public void setOpeningFloat(BigDecimal openingFloat) { this.openingFloat = openingFloat; }

    public boolean isAllowSharedDrawer() { return allowSharedDrawer; }
    public void setAllowSharedDrawer(boolean allowSharedDrawer) { this.allowSharedDrawer = allowSharedDrawer; }

    public boolean isManagerApprovalRequired() { return managerApprovalRequired; }
    public void setManagerApprovalRequired(boolean managerApprovalRequired) { this.managerApprovalRequired = managerApprovalRequired; }

    public boolean isManagerOrAdmin() { return managerOrAdmin; }
    public void setManagerOrAdmin(boolean managerOrAdmin) { this.managerOrAdmin = managerOrAdmin; }

    public boolean isCanOpenCashDrawer() { return canOpenCashDrawer; }
    public void setCanOpenCashDrawer(boolean canOpenCashDrawer) { this.canOpenCashDrawer = canOpenCashDrawer; }

    public boolean isCanOpenNonCash() { return canOpenNonCash; }
    public void setCanOpenNonCash(boolean canOpenNonCash) { this.canOpenNonCash = canOpenNonCash; }

    public boolean isCanJoinExisting() { return canJoinExisting; }
    public void setCanJoinExisting(boolean canJoinExisting) { this.canJoinExisting = canJoinExisting; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
