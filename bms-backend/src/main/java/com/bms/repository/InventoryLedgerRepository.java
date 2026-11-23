package com.bms.repository;

import com.bms.entity.InventoryLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InventoryLedgerRepository extends JpaRepository<InventoryLedger, Long> {

    /** Running balance anchor: the most recent posting for a product. */
    Optional<InventoryLedger> findTopByProductIdOrderByIdDesc(Long productId);

    boolean existsByProductId(Long productId);

    Optional<InventoryLedger> findBySourceTypeAndSourceIdAndEventTypeAndProductIdAndLineKey(
            String sourceType, Long sourceId, InventoryLedger.EventType eventType, Long productId, String lineKey);

    List<InventoryLedger> findByProductIdOrderByOccurredAtAscIdAsc(Long productId);

    /** BUG-8 repair: nearest ledger cost at or before a given date. */
    List<InventoryLedger> findByProductIdAndOccurredAtLessThanEqualOrderByOccurredAtDescIdDesc(
            Long productId, LocalDateTime at);

    List<InventoryLedger> findByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscIdAsc(
            LocalDateTime from, LocalDateTime to);

    List<InventoryLedger> findByProductIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtAscIdAsc(
            Long productId, LocalDateTime from, LocalDateTime to);

    @Query("SELECT COUNT(l) > 0 FROM InventoryLedger l "
            + "WHERE l.sourceType = :sourceType AND l.sourceId = :sourceId "
            + "AND l.eventType = :eventType AND l.productId = :productId AND l.lineKey = :lineKey")
    boolean existsPosting(@Param("sourceType") String sourceType,
                          @Param("sourceId") Long sourceId,
                          @Param("eventType") InventoryLedger.EventType eventType,
                          @Param("productId") Long productId,
                          @Param("lineKey") String lineKey);

    @Query("SELECT SUM(l.qtyDelta), SUM(l.valueDelta) FROM InventoryLedger l WHERE l.productId = :productId")
    Object[] sumDeltasByProduct(@Param("productId") Long productId);

    @Query("SELECT DISTINCT l.productId FROM InventoryLedger l")
    List<Long> findDistinctProductIds();
}