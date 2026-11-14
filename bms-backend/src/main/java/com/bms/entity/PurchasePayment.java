package com.bms.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * O3: a single payment (partial or full) recorded against a supplier purchase
 * invoice, mirroring {@link ArPayment}. Every payment reduces the outstanding
 * payable (totalAmount - returnedAmount - paid) and advances the derived
 * Purchase.paymentStatus (PENDING -> PARTIAL -> PAID). Amounts are in the
 * purchase's invoice currency; the GL settlement converts at the purchase's
 * exchange rate.
 */
@Entity
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Table(name = "purchase_payments", indexes = {
    @Index(name = "idx_purchase_payment_purchase", columnList = "purchase_id"),
    @Index(name = "idx_purchase_payment_date", columnList = "payment_date"),
    @Index(name = "idx_purchase_payment_recorded_by", columnList = "recorded_by_id")
})
public class PurchasePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id", nullable = false)
    private Purchase purchase;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30,
            columnDefinition = "varchar(30) not null default 'CASH'")
    private PaymentMethod paymentMethod = PaymentMethod.CASH;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_shift_id")
    private CashShift employeeShift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "drawer_movement_id")
    private CashDrawerMovement drawerMovement;

    @Column(name = "payment_date", nullable = false)
    private LocalDateTime paymentDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by_id", nullable = false)
    private User recordedBy;

    @Column(length = 255)
    private String notes;

    @PrePersist
    protected void onCreate() {
        if (paymentDate == null) {
            paymentDate = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Purchase getPurchase() { return purchase; }
    public void setPurchase(Purchase purchase) { this.purchase = purchase; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDateTime getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDateTime paymentDate) { this.paymentDate = paymentDate; }

    public User getRecordedBy() { return recordedBy; }
    public void setRecordedBy(User recordedBy) { this.recordedBy = recordedBy; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod != null ? paymentMethod : PaymentMethod.CASH;
    }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public CashShift getEmployeeShift() { return employeeShift; }
    public void setEmployeeShift(CashShift employeeShift) { this.employeeShift = employeeShift; }

    public CashDrawerMovement getDrawerMovement() { return drawerMovement; }
    public void setDrawerMovement(CashDrawerMovement drawerMovement) { this.drawerMovement = drawerMovement; }
}