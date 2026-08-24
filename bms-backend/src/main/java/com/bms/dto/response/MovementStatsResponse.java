package com.bms.dto.response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MovementStatsResponse {

    private int days;
    private long totalIn;
    private long totalOut;
    private long netChange;
    private List<DailyMovement> daily = new ArrayList<>();
    private List<ReferenceCount> byReference = new ArrayList<>();

    public static class DailyMovement {
        private String date;
        private long inQty;
        private long outQty;

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }

        public long getInQty() { return inQty; }
        public void setInQty(long inQty) { this.inQty = inQty; }

        public long getOutQty() { return outQty; }
        public void setOutQty(long outQty) { this.outQty = outQty; }
    }

    public static class ReferenceCount {
        private String referenceType;
        private long count;
        private long quantity;

        public String getReferenceType() { return referenceType; }
        public void setReferenceType(String referenceType) { this.referenceType = referenceType; }

        public long getCount() { return count; }
        public void setCount(long count) { this.count = count; }

        public long getQuantity() { return quantity; }
        public void setQuantity(long quantity) { this.quantity = quantity; }
    }

    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }

    public long getTotalIn() { return totalIn; }
    public void setTotalIn(long totalIn) { this.totalIn = totalIn; }

    public long getTotalOut() { return totalOut; }
    public void setTotalOut(long totalOut) { this.totalOut = totalOut; }

    public long getNetChange() { return netChange; }
    public void setNetChange(long netChange) { this.netChange = netChange; }

    public List<DailyMovement> getDaily() { return daily; }
    public void setDaily(List<DailyMovement> daily) { this.daily = daily; }

    public List<ReferenceCount> getByReference() { return byReference; }
    public void setByReference(List<ReferenceCount> byReference) { this.byReference = byReference; }
}
