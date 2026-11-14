package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "sale_return_items", indexes = {
    @Index(name = "idx_sale_return_item_return", columnList = "sale_return_id"),
    @Index(name = "idx_sale_return_item_sale_item", columnList = "sale_item_id")
})
public class SaleReturnItem {

    /** P9: what happens to the returned units. Defaults to RESTOCK so the cashier flow is unchanged. */
    public enum ReturnDisposition { RESTOCK, QUARANTINE, WRITE_OFF }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_return_id", nullable = false)
    private SaleReturn saleReturn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_item_id", nullable = false)
    private SaleItem saleItem;

    @Column(name = "quantity_returned", nullable = false)
    private Integer quantityReturned;

    @Column(name = "return_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal returnAmount;

    /** P9: per-line condition. Null on legacy rows is treated as RESTOCK. */
    @Column(name = "disposition", length = 20)
    @Enumerated(EnumType.STRING)
    private ReturnDisposition disposition;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public SaleReturn getSaleReturn() { return saleReturn; }
    public void setSaleReturn(SaleReturn saleReturn) { this.saleReturn = saleReturn; }

    public SaleItem getSaleItem() { return saleItem; }
    public void setSaleItem(SaleItem saleItem) { this.saleItem = saleItem; }

    public Integer getQuantityReturned() { return quantityReturned; }
    public void setQuantityReturned(Integer quantityReturned) { this.quantityReturned = quantityReturned; }

    public BigDecimal getReturnAmount() { return returnAmount; }
    public void setReturnAmount(BigDecimal returnAmount) { this.returnAmount = returnAmount; }

    public ReturnDisposition getDisposition() { return disposition; }
    public void setDisposition(ReturnDisposition disposition) { this.disposition = disposition; }
}
