package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "expenses")
public class Expense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ExpenseCategory category;

    @Column(length = 255)
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "receipt_image", columnDefinition = "LONGBLOB")
    private byte[] receiptImage;

    @Column(name = "receipt_image_type", length = 10)
    private String receiptImageType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * O6: marks whether this expense has been settled (paid out of the drawer).
     * Expenses are posted to GL as Dr Operating Expense / Cr Expense Payable when
     * created; settling clears the payable (Dr Expense Payable / Cr Cash). Default
     * false — expenses are never auto-settled.
     */
    @Column(name = "settled", nullable = false)
    private Boolean settled = false;

    @Column(name = "ledger_revision", nullable = false, columnDefinition = "BIGINT DEFAULT 0")
    private long ledgerRevision;

    /**
     * O4: marks a cost that was already capitalised into inventory through a
     * purchase (freight/duty/insurance). Capitalised expenses are excluded
     * from the operating P&L, the profit ledger and the GL expense entries so
     * the cost is never counted twice.
     */
    @Column(name = "capitalized")
    private Boolean capitalized = false;

    /**
     * O4: optional logical reference to the purchase_expenses row that already
     * capitalised this cost. Kept as a plain id (no DB constraint) because
     * purchase expenses are cascade-removed when a purchase is deleted.
     */
    @Column(name = "purchase_expense_id")
    private Long purchaseExpenseId;

    /**
     * F28: links a PURCHASE_VARIANCE expense back to the purchase that generated it,
     * so deleting a purchase can reverse exactly its variance expense rows.
     */
    @Column(name = "source_purchase_id")
    private Long sourcePurchaseId;

    public long getLedgerRevision() { return ledgerRevision; }
    public void setLedgerRevision(long ledgerRevision) { this.ledgerRevision = ledgerRevision; }

    public Boolean getCapitalized() { return capitalized; }
    public void setCapitalized(Boolean capitalized) { this.capitalized = capitalized; }

    public Long getPurchaseExpenseId() { return purchaseExpenseId; }
    public void setPurchaseExpenseId(Long purchaseExpenseId) { this.purchaseExpenseId = purchaseExpenseId; }

    public Long getSourcePurchaseId() { return sourcePurchaseId; }
    public void setSourcePurchaseId(Long v) { this.sourcePurchaseId = v; }

    /** True when this cost belongs to inventory valuation, not the P&L. */
    public boolean isCapitalized() {
        return Boolean.TRUE.equals(capitalized) || purchaseExpenseId != null;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum ExpenseCategory {
        RENT,
        UTILITIES,
        TRAVEL,
        TAXES,
        SALARY,
        SUPPLIES,
        MAINTENANCE,
        MARKETING,
        PURCHASE_VARIANCE,
        OTHER
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ExpenseCategory getCategory() { return category; }
    public void setCategory(ExpenseCategory category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDate getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public byte[] getReceiptImage() { return receiptImage; }
    public void setReceiptImage(byte[] receiptImage) { this.receiptImage = receiptImage; }

    public String getReceiptImageType() { return receiptImageType; }
    public void setReceiptImageType(String receiptImageType) { this.receiptImageType = receiptImageType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public Boolean getSettled() { return settled != null ? settled : false; }
    public void setSettled(Boolean settled) { this.settled = settled != null ? settled : false; }
}
