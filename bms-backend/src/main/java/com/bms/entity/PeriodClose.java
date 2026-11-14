package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * O8 Stage 5: a closed accounting period. The presence of a row freezes the
 * period's revenue/COGS/expense totals; back-dated writes into it are rejected
 * by PeriodGuard. Reopening deletes the row (ADMIN only, with a logged reason).
 */
@Entity
@Table(name = "period_close")
public class PeriodClose {

    @Id
    @Column(name = "period", length = 7)
    private String period;

    @Column(name = "closed_at", nullable = false)
    private LocalDateTime closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "frozen_revenue", nullable = false, precision = 19, scale = 4)
    private BigDecimal frozenRevenue = BigDecimal.ZERO;

    @Column(name = "frozen_cogs", nullable = false, precision = 19, scale = 4)
    private BigDecimal frozenCogs = BigDecimal.ZERO;

    @Column(name = "frozen_expense", nullable = false, precision = 19, scale = 4)
    private BigDecimal frozenExpense = BigDecimal.ZERO;

    @PrePersist
    protected void onCreate() {
        if (closedAt == null) {
            closedAt = LocalDateTime.now();
        }
    }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public Long getClosedBy() { return closedBy; }
    public void setClosedBy(Long closedBy) { this.closedBy = closedBy; }
    public BigDecimal getFrozenRevenue() { return frozenRevenue; }
    public void setFrozenRevenue(BigDecimal frozenRevenue) { this.frozenRevenue = frozenRevenue; }
    public BigDecimal getFrozenCogs() { return frozenCogs; }
    public void setFrozenCogs(BigDecimal frozenCogs) { this.frozenCogs = frozenCogs; }
    public BigDecimal getFrozenExpense() { return frozenExpense; }
    public void setFrozenExpense(BigDecimal frozenExpense) { this.frozenExpense = frozenExpense; }
}