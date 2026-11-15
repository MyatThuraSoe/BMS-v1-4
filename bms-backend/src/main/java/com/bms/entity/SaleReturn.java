package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sale_returns", indexes = {
    @Index(name = "idx_sale_return_sale", columnList = "sale_id"),
    @Index(name = "idx_sale_return_date", columnList = "return_date"),
    @Index(name = "idx_sale_return_user", columnList = "returned_by")
})
public class SaleReturn {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "returned_by", nullable = false)
    private User returnedBy;

    @Column(name = "return_date", nullable = false)
    private LocalDateTime returnDate;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "total_return_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalReturnAmount;

    @Column(name = "debt_reduction_amount", precision = 10, scale = 2)
    private BigDecimal debtReductionAmount = BigDecimal.ZERO;

    @Column(name = "refund_amount", precision = 10, scale = 2)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", nullable = false, length = 30,
            columnDefinition = "varchar(30) not null default 'CASH'")
    private PaymentMethod refundMethod = PaymentMethod.CASH;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_shift_id")
    private CashShift employeeShift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "drawer_movement_id")
    private CashDrawerMovement drawerMovement;

    @Column(name = "external_reference", length = 255)
    private String externalReference;

    @OneToMany(mappedBy = "saleReturn", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SaleReturnItem> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (returnDate == null) {
            returnDate = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Sale getSale() { return sale; }
    public void setSale(Sale sale) { this.sale = sale; }

    public User getReturnedBy() { return returnedBy; }
    public void setReturnedBy(User returnedBy) { this.returnedBy = returnedBy; }

    public LocalDateTime getReturnDate() { return returnDate; }
    public void setReturnDate(LocalDateTime returnDate) { this.returnDate = returnDate; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public BigDecimal getTotalReturnAmount() { return totalReturnAmount; }
    public void setTotalReturnAmount(BigDecimal totalReturnAmount) { this.totalReturnAmount = totalReturnAmount; }

    public BigDecimal getDebtReductionAmount() { return debtReductionAmount != null ? debtReductionAmount : BigDecimal.ZERO; }
    public void setDebtReductionAmount(BigDecimal debtReductionAmount) { this.debtReductionAmount = debtReductionAmount != null ? debtReductionAmount : BigDecimal.ZERO; }

    public BigDecimal getRefundAmount() { return refundAmount != null ? refundAmount : BigDecimal.ZERO; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount != null ? refundAmount : BigDecimal.ZERO; }

    public PaymentMethod getRefundMethod() {
        return refundMethod != null ? refundMethod : PaymentMethod.CASH;
    }
    public void setRefundMethod(PaymentMethod refundMethod) { this.refundMethod = refundMethod; }

    public CashShift getEmployeeShift() { return employeeShift; }
    public void setEmployeeShift(CashShift employeeShift) { this.employeeShift = employeeShift; }

    public CashDrawerMovement getDrawerMovement() { return drawerMovement; }
    public void setDrawerMovement(CashDrawerMovement drawerMovement) { this.drawerMovement = drawerMovement; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }

    public List<SaleReturnItem> getItems() { return items; }
    public void setItems(List<SaleReturnItem> items) { this.items = items; }
}
