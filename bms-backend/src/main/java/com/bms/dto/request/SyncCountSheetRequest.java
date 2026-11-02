package com.bms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Offline/mobile sync payload. The syncKey makes the write idempotent: sending
 * the same sheet twice returns the already-applied sheet instead of adjusting
 * stock twice.
 */
public class SyncCountSheetRequest {

    @NotBlank(message = "Sync key is required")
    private String syncKey;

    private String countType = "MANUAL";
    private String notes;
    private LocalDateTime submittedAt;

    @NotEmpty(message = "Count entries are required")
    @Valid
    private List<Entry> items;

    public String getSyncKey() { return syncKey; }
    public void setSyncKey(String syncKey) { this.syncKey = syncKey; }
    public String getCountType() { return countType; }
    public void setCountType(String countType) { this.countType = countType; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public List<Entry> getItems() { return items; }
    public void setItems(List<Entry> items) { this.items = items; }

    public static class Entry {
        private Long productId;
        private Integer systemQuantity;
        private Integer countedQuantity;
        private String reasonCode;
        private String note;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public Integer getSystemQuantity() { return systemQuantity; }
        public void setSystemQuantity(Integer systemQuantity) { this.systemQuantity = systemQuantity; }
        public Integer getCountedQuantity() { return countedQuantity; }
        public void setCountedQuantity(Integer countedQuantity) { this.countedQuantity = countedQuantity; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }
}
