package com.bms.dto.request;

import java.util.List;

public class CountSheetCreateRequest {
    private String countType = "MANUAL";
    private List<Long> productIds;
    private String notes;

    public String getCountType() { return countType; }
    public void setCountType(String countType) { this.countType = countType; }
    public List<Long> getProductIds() { return productIds; }
    public void setProductIds(List<Long> productIds) { this.productIds = productIds; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
