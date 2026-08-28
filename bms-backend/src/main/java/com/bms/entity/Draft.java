package com.bms.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "drafts", indexes = {
    @Index(name = "idx_draft_created_at", columnList = "createdAt"),
    @Index(name = "idx_draft_cashier", columnList = "cashier_id")
})
public class Draft {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cashier_id", nullable = false)
    private Long cashierId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(length = 1000)
    private String notes;

    private Long customerId;

    @Column(nullable = false)
    private Boolean isActive = true;

    @OneToMany(mappedBy = "draft", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<DraftItem> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public Long getCashierId() { return cashierId; }
    public void setCashierId(Long cashierId) { this.cashierId = cashierId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public List<DraftItem> getItems() { return items; }
}