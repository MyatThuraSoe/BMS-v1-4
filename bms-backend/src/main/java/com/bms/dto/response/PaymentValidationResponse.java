package com.bms.dto.response;

public class PaymentValidationResponse {
    private boolean valid;
    private String code;
    private String message;
    private Long shiftId;
    private String shiftType;
    private boolean cashAllowed;
    private Long drawerSessionId;
    private String drawerName;
    private boolean hasActiveDrawerSession;

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getShiftId() { return shiftId; }
    public void setShiftId(Long shiftId) { this.shiftId = shiftId; }

    public String getShiftType() { return shiftType; }
    public void setShiftType(String shiftType) { this.shiftType = shiftType; }

    public boolean isCashAllowed() { return cashAllowed; }
    public void setCashAllowed(boolean cashAllowed) { this.cashAllowed = cashAllowed; }

    public Long getDrawerSessionId() { return drawerSessionId; }
    public void setDrawerSessionId(Long drawerSessionId) { this.drawerSessionId = drawerSessionId; }

    public String getDrawerName() { return drawerName; }
    public void setDrawerName(String drawerName) { this.drawerName = drawerName; }

    public boolean isHasActiveDrawerSession() { return hasActiveDrawerSession; }
    public void setHasActiveDrawerSession(boolean hasActiveDrawerSession) { this.hasActiveDrawerSession = hasActiveDrawerSession; }
}
