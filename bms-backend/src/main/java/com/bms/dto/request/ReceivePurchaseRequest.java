package com.bms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public class ReceivePurchaseRequest {

    @NotEmpty(message = "At least one received item is required")
    @Valid
    private List<ReceiveItem> items;

    /** Required only when received quantity exceeds the ordered quantity. */
    private String managerUsername;
    private String managerPassword;

    private String notes;

    /**
     * Short-shipment decision. When true the outstanding ordered quantity is
     * cancelled and the PO is closed; otherwise it stays open for a future
     * delivery.
     */
    private Boolean cancelRemainder = false;

    public List<ReceiveItem> getItems() { return items; }
    public void setItems(List<ReceiveItem> items) { this.items = items; }
    public String getManagerUsername() { return managerUsername; }
    public void setManagerUsername(String managerUsername) { this.managerUsername = managerUsername; }
    public String getManagerPassword() { return managerPassword; }
    public void setManagerPassword(String managerPassword) { this.managerPassword = managerPassword; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Boolean getCancelRemainder() { return cancelRemainder != null && cancelRemainder; }
    public void setCancelRemainder(Boolean cancelRemainder) { this.cancelRemainder = cancelRemainder; }

    public static class ReceiveItem {
        @NotNull(message = "Purchase item id is required")
        private Long purchaseItemId;

        @NotNull(message = "Received quantity is required")
        @PositiveOrZero(message = "Received quantity cannot be negative")
        private Integer receivedQuantity;

        /** Units that go straight to quarantine instead of sellable stock. */
        @PositiveOrZero(message = "Quarantine quantity cannot be negative")
        private Integer quarantineQuantity = 0;

        /** Destination for the sellable portion. Defaults to the SHOP location. */
        private Long locationId;

        private String reasonCode;

        public Long getPurchaseItemId() { return purchaseItemId; }
        public void setPurchaseItemId(Long purchaseItemId) { this.purchaseItemId = purchaseItemId; }
        public Integer getReceivedQuantity() { return receivedQuantity; }
        public void setReceivedQuantity(Integer receivedQuantity) { this.receivedQuantity = receivedQuantity; }
        public Integer getQuarantineQuantity() { return quarantineQuantity != null ? quarantineQuantity : 0; }
        public void setQuarantineQuantity(Integer quarantineQuantity) { this.quarantineQuantity = quarantineQuantity; }
        public Long getLocationId() { return locationId; }
        public void setLocationId(Long locationId) { this.locationId = locationId; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    }
}
