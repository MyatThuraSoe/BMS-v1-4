package com.bms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class RefundRequest {
    
    @NotBlank(message = "Reason is required")
    private String reason;
    
    @NotEmpty(message = "At least one item must be refunded")
    private List<RefundItemRequest> items;
    
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    
    public List<RefundItemRequest> getItems() { return items; }
    public void setItems(List<RefundItemRequest> items) { this.items = items; }
}
