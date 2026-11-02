package com.bms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class GlMappingRequest {

    @NotBlank(message = "Event type is required")
    private String eventType;

    @NotNull(message = "GL account is required")
    private Long accountId;

    private String description;

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
