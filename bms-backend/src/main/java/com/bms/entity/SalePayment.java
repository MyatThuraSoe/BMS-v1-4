package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One line of a (possibly split) sale payment. Cash lines are linked to the
 * drawer session and the exact drawer movement they created; non-cash lines
 * never touch the drawer.
 */
@Entity
@Table(name = "sale_payments", indexes = {
    @Index(name = "idx_sale_payment_sale", columnList = "sale_id"),
    @Index(name = "idx_sale_payment_method", columnList = "payment_method"),
    @Index(name = "idx_sale_payment_drawer_session", columnList = "drawer_session_id"),
    @Index(name = "idx_sale_payment_paid_at", columnList = "paid_at")
})
public class SalePayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    private PaymentMethod paymentMethod;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "external_reference", length = 255)
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 30)
    private PaymentStatus paymentStatus = PaymentStatus.COMPLETED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "drawer_session_id")
    private CashDrawerSession drawerSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "drawer_movement_id")
    private CashDrawerMovement drawerMovement;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    @PrePersist
    protected void onCreate() {
        if (paidAt == null) {
            paidAt = LocalDateTime.now();
        }
        if (paymentStatus == null) {
            paymentStatus = PaymentStatus.COMPLETED;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Sale getSale() { return sale; }
    public void setSale(Sale sale) { this.sale = sale; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }

    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }

    public CashDrawerSession getDrawerSession() { return drawerSession; }
    public void setDrawerSession(CashDrawerSession drawerSession) { this.drawerSession = drawerSession; }

    public CashDrawerMovement getDrawerMovement() { return drawerMovement; }
    public void setDrawerMovement(CashDrawerMovement drawerMovement) { this.drawerMovement = drawerMovement; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
}
