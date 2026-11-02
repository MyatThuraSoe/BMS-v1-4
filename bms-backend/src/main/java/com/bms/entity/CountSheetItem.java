package com.bms.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Table(name = "count_sheet_items", uniqueConstraints = {
    @UniqueConstraint(name = "uk_count_sheet_item", columnNames = {"count_sheet_id", "product_id"})
}, indexes = {
    @Index(name = "idx_count_item_sheet", columnList = "count_sheet_id"),
    @Index(name = "idx_count_item_product", columnList = "product_id")
})
public class CountSheetItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "count_sheet_id", nullable = false)
    @JsonIgnore
    private CountSheet countSheet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "system_quantity", nullable = false)
    private Integer systemQuantity = 0;

    @Column(name = "counted_quantity")
    private Integer countedQuantity;

    @Column
    private Integer variance;

    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    @Column(name = "valuation_amount", precision = 19, scale = 2)
    private BigDecimal valuationAmount;

    @Column(name = "counted_at")
    private LocalDateTime countedAt;

    @Column(length = 500)
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public CountSheet getCountSheet() { return countSheet; }
    public void setCountSheet(CountSheet countSheet) { this.countSheet = countSheet; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public Integer getSystemQuantity() { return systemQuantity != null ? systemQuantity : 0; }
    public void setSystemQuantity(Integer systemQuantity) { this.systemQuantity = systemQuantity; }
    public Integer getCountedQuantity() { return countedQuantity; }
    public void setCountedQuantity(Integer countedQuantity) { this.countedQuantity = countedQuantity; }
    public Integer getVariance() { return variance; }
    public void setVariance(Integer variance) { this.variance = variance; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public BigDecimal getValuationAmount() { return valuationAmount; }
    public void setValuationAmount(BigDecimal valuationAmount) { this.valuationAmount = valuationAmount; }
    public LocalDateTime getCountedAt() { return countedAt; }
    public void setCountedAt(LocalDateTime countedAt) { this.countedAt = countedAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
