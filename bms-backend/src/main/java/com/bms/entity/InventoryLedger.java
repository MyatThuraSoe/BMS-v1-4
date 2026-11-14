package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * P1/O8 Stage 1: append-only inventory value ledger. One row per valued stock
 * event, carrying the signed quantity/value delta and the running balance after
 * the event. {@code unitCostAfter} (= valueAfter / qtyAfter over all locations)
 * is the authoritative weighted-average cost; {@code Product.costPrice} is only
 * a cache of it. Types are portable across H2 / SQLite / MySQL (ddl-auto=update).
 */
@Entity
@Table(name = "inventory_ledger",
    uniqueConstraints = @UniqueConstraint(name = "uq_inventory_ledger_source",
        columnNames = {"source_type", "source_id", "event_type", "product_id", "line_key"}),
    indexes = {
        @Index(name = "idx_inventory_ledger_product", columnList = "product_id"),
        @Index(name = "idx_inventory_ledger_occurred", columnList = "occurred_at"),
        @Index(name = "idx_inventory_ledger_source", columnList = "source_type, source_id")
    })
public class InventoryLedger {

    public enum EventType {
        RECEIPT, RTV, SALE, RETURN, VOID, ADJUSTMENT, COUNT, TRANSFER, REVALUATION, OPENING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "location_id")
    private Long locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private EventType eventType;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    /** Disambiguates multiple lines of the same product within one source. */
    @Column(name = "line_key", nullable = false, length = 60)
    private String lineKey = "";

    @Column(name = "qty_delta", nullable = false)
    private Integer qtyDelta;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "value_delta", nullable = false, precision = 19, scale = 6)
    private BigDecimal valueDelta;

    @Column(name = "qty_after", nullable = false)
    private Integer qtyAfter;

    @Column(name = "value_after", nullable = false, precision = 19, scale = 6)
    private BigDecimal valueAfter;

    @Column(name = "unit_cost_after", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitCostAfter;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "posted_at", nullable = false)
    private LocalDateTime postedAt;

    @PrePersist
    protected void onCreate() {
        if (postedAt == null) {
            postedAt = LocalDateTime.now();
        }
        if (lineKey == null) {
            lineKey = "";
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public Long getLocationId() { return locationId; }
    public void setLocationId(Long locationId) { this.locationId = locationId; }
    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getLineKey() { return lineKey; }
    public void setLineKey(String lineKey) { this.lineKey = lineKey; }
    public Integer getQtyDelta() { return qtyDelta; }
    public void setQtyDelta(Integer qtyDelta) { this.qtyDelta = qtyDelta; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    public BigDecimal getValueDelta() { return valueDelta; }
    public void setValueDelta(BigDecimal valueDelta) { this.valueDelta = valueDelta; }
    public Integer getQtyAfter() { return qtyAfter; }
    public void setQtyAfter(Integer qtyAfter) { this.qtyAfter = qtyAfter; }
    public BigDecimal getValueAfter() { return valueAfter; }
    public void setValueAfter(BigDecimal valueAfter) { this.valueAfter = valueAfter; }
    public BigDecimal getUnitCostAfter() { return unitCostAfter; }
    public void setUnitCostAfter(BigDecimal unitCostAfter) { this.unitCostAfter = unitCostAfter; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public LocalDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(LocalDateTime postedAt) { this.postedAt = postedAt; }
}