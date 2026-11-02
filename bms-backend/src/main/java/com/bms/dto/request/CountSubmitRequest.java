package com.bms.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public class CountSubmitRequest {

    @NotEmpty(message = "Count entries are required")
    @Valid
    private List<Entry> items;

    private String notes;

    public List<Entry> getItems() { return items; }
    public void setItems(List<Entry> items) { this.items = items; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public static class Entry {
        @NotNull(message = "Count sheet item id is required")
        private Long itemId;

        @NotNull(message = "Counted quantity is required")
        @PositiveOrZero(message = "Counted quantity cannot be negative")
        private Integer countedQuantity;

        private String reasonCode;
        private String note;

        public Long getItemId() { return itemId; }
        public void setItemId(Long itemId) { this.itemId = itemId; }
        public Integer getCountedQuantity() { return countedQuantity; }
        public void setCountedQuantity(Integer countedQuantity) { this.countedQuantity = countedQuantity; }
        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }
}
