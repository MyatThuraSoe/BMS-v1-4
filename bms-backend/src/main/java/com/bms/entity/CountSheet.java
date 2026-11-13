package com.bms.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Table(name = "count_sheets", indexes = {
    @Index(name = "idx_count_sheet_number", columnList = "sheet_number"),
    @Index(name = "idx_count_sheet_status", columnList = "status"),
    @Index(name = "idx_count_sheet_date", columnList = "scheduled_date")
})
public class CountSheet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_number", nullable = false, unique = true, length = 50)
    private String sheetNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CountStatus status = CountStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "count_type", nullable = false, length = 30)
    private CountType countType = CountType.MANUAL;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(length = 1000)
    private String notes;

    /** Client-generated key for idempotent offline sync. */
    @Column(name = "sync_key", unique = true, length = 100)
    private String syncKey;

    @Column(name = "variance_value", precision = 19, scale = 6)
    private BigDecimal varianceValue = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "countSheet", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CountSheetItem> items = new ArrayList<>();

    public enum CountStatus {
        DRAFT, IN_PROGRESS, PENDING_REVIEW, APPROVED, CANCELLED
    }

    public enum CountType {
        ABC_A, ABC_B, ABC_C, FULL, MANUAL
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (scheduledDate == null) scheduledDate = LocalDate.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSheetNumber() { return sheetNumber; }
    public void setSheetNumber(String sheetNumber) { this.sheetNumber = sheetNumber; }
    public CountStatus getStatus() { return status != null ? status : CountStatus.DRAFT; }
    public void setStatus(CountStatus status) { this.status = status; }
    public CountType getCountType() { return countType != null ? countType : CountType.MANUAL; }
    public void setCountType(CountType countType) { this.countType = countType; }
    public LocalDate getScheduledDate() { return scheduledDate; }
    public void setScheduledDate(LocalDate scheduledDate) { this.scheduledDate = scheduledDate; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(Long submittedBy) { this.submittedBy = submittedBy; }
    public Long getApprovedBy() { return approvedBy; }
    public void setApprovedBy(Long approvedBy) { this.approvedBy = approvedBy; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getSyncKey() { return syncKey; }
    public void setSyncKey(String syncKey) { this.syncKey = syncKey; }
    public BigDecimal getVarianceValue() { return varianceValue != null ? varianceValue : BigDecimal.ZERO; }
    public void setVarianceValue(BigDecimal varianceValue) { this.varianceValue = varianceValue; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<CountSheetItem> getItems() { return items; }
    public void setItems(List<CountSheetItem> items) { this.items = items; }
}
