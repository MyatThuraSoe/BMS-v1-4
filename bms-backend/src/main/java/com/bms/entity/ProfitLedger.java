package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * P1/O8 Stage 1: append-only profit ledger. One row per profit-affecting event,
 * dated by {@code occurredAt} and bucketed into a {@code period} (YYYY-MM).
 * Returns and voids post their own negative rows on their own date, so a later
 * return never mutates an earlier period's revenue/COGS. Reports read this
 * ledger instead of re-deriving from mutable SaleItem flags.
 */
@Entity
@Table(name = "profit_ledger",
    uniqueConstraints = @UniqueConstraint(name = "uq_profit_ledger_source",
        columnNames = {"source_type", "source_id", "event_type", "product_id", "line_key"}),
    indexes = {
        @Index(name = "idx_profit_ledger_period", columnList = "period"),
        @Index(name = "idx_profit_ledger_occurred", columnList = "occurred_at"),
        @Index(name = "idx_profit_ledger_product", columnList = "product_id"),
        @Index(name = "idx_profit_ledger_source", columnList = "source_type, source_id")
    })
public class ProfitLedger {

    public enum EventType {
        SALE, RETURN, VOID, WRITE_OFF, EXPENSE, REVALUATION, PURCHASE_VARIANCE, INVENTORY_GAIN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period", nullable = false, length = 7)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private EventType eventType;

    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "line_key", nullable = false, length = 60)
    private String lineKey = "";

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "posting_key", nullable = false, unique = true, length = 200)
    private String postingKey;

    @Column(name = "net_revenue", nullable = false, precision = 19, scale = 4)
    private BigDecimal netRevenue = BigDecimal.ZERO;

    @Column(name = "cogs", nullable = false, precision = 19, scale = 4)
    private BigDecimal cogs = BigDecimal.ZERO;

    @Column(name = "expense", nullable = false, precision = 19, scale = 4)
    private BigDecimal expense = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_account", length = 50)
    private GlMapping.EventType settlementAccount;

    @Column(name = "settlement_cash", precision = 19, scale = 4)
    private BigDecimal settlementCash;

    @Column(name = "settlement_clearing", precision = 19, scale = 4)
    private BigDecimal settlementClearing;

    @Column(name = "settlement_ar", precision = 19, scale = 4)
    private BigDecimal settlementAr;

    @Column(name = "reference", length = 100)
    private String reference;

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
        if (netRevenue == null) {
            netRevenue = BigDecimal.ZERO;
        }
        if (cogs == null) {
            cogs = BigDecimal.ZERO;
        }
        if (expense == null) {
            expense = BigDecimal.ZERO;
        }
        postingKey = sourceType + ":" + sourceId + ":" + eventType + ":" + productId + ":" + lineKey;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getPostingKey() { return postingKey; }
    public String getLineKey() { return lineKey; }
    public void setLineKey(String lineKey) { this.lineKey = lineKey; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public GlMapping.EventType getSettlementAccount() { return settlementAccount; }
    public void setSettlementAccount(GlMapping.EventType settlementAccount) { this.settlementAccount = settlementAccount; }
    public BigDecimal getSettlementCash() { return settlementCash; }
    public void setSettlementCash(BigDecimal settlementCash) { this.settlementCash = settlementCash; }
    public BigDecimal getSettlementClearing() { return settlementClearing; }
    public void setSettlementClearing(BigDecimal settlementClearing) { this.settlementClearing = settlementClearing; }
    public BigDecimal getSettlementAr() { return settlementAr; }
    public void setSettlementAr(BigDecimal settlementAr) { this.settlementAr = settlementAr; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getNetRevenue() { return netRevenue; }
    public void setNetRevenue(BigDecimal netRevenue) { this.netRevenue = netRevenue; }
    public BigDecimal getCogs() { return cogs; }
    public void setCogs(BigDecimal cogs) { this.cogs = cogs; }
    public BigDecimal getExpense() { return expense; }
    public void setExpense(BigDecimal expense) { this.expense = expense; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public LocalDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(LocalDateTime postedAt) { this.postedAt = postedAt; }
}