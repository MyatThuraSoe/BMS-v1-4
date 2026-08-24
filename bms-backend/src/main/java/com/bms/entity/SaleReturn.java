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

    public List<SaleReturnItem> getItems() { return items; }
    public void setItems(List<SaleReturnItem> items) { this.items = items; }
}
