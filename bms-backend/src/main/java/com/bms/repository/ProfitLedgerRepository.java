package com.bms.repository;

import com.bms.entity.ProfitLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ProfitLedgerRepository extends JpaRepository<ProfitLedger, Long> {

    @Query("SELECT COUNT(p) > 0 FROM ProfitLedger p "
            + "WHERE p.sourceType = :sourceType AND p.sourceId = :sourceId "
            + "AND p.eventType = :eventType AND p.lineKey = :lineKey "
            + "AND ((:productId IS NULL AND p.productId IS NULL) OR p.productId = :productId)")
    boolean existsPosting(@Param("sourceType") String sourceType,
                          @Param("sourceId") Long sourceId,
                          @Param("eventType") ProfitLedger.EventType eventType,
                          @Param("productId") Long productId,
                          @Param("lineKey") String lineKey);

    List<ProfitLedger> findByPeriodOrderByOccurredAtAscIdAsc(String period);

    /** N1 repair: every ledger row of one source kind, in the order they were posted. */
    List<ProfitLedger> findByEventTypeAndSourceTypeOrderByIdAsc(ProfitLedger.EventType eventType, String sourceType);

    List<ProfitLedger> findByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscIdAsc(
            LocalDateTime from, LocalDateTime to);

    /** Period totals: [netRevenue, cogs, expense]; null when the period has no rows. */
    @Query("SELECT SUM(p.netRevenue), SUM(p.cogs), SUM(p.expense) FROM ProfitLedger p WHERE p.period = :period")
    Object[] sumByPeriod(@Param("period") String period);

    /** Half-open [from, to) date-range totals: [netRevenue, cogs, expense]. */
    @Query("SELECT SUM(p.netRevenue), SUM(p.cogs), SUM(p.expense) FROM ProfitLedger p "
            + "WHERE p.occurredAt >= :from AND p.occurredAt < :to")
    Object[] sumByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Per-period totals for the trend: [period, netRevenue, cogs, expense]. */
    @Query("SELECT p.period, SUM(p.netRevenue), SUM(p.cogs), SUM(p.expense) FROM ProfitLedger p "
            + "WHERE p.occurredAt >= :from AND p.occurredAt < :to GROUP BY p.period ORDER BY p.period ASC")
    List<Object[]> sumGroupedByPeriod(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Per-product totals: [productId, quantity, netRevenue, cogs]. */
    @Query("SELECT p.productId, SUM(p.quantity), SUM(p.netRevenue), SUM(p.cogs) FROM ProfitLedger p "
            + "WHERE p.occurredAt >= :from AND p.occurredAt < :to GROUP BY p.productId")
    List<Object[]> sumGroupedByProduct(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}