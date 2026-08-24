package com.bms.repository;

import com.bms.entity.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
    Page<StockMovement> findByProductId(Long productId, Pageable pageable);
    
    @Query("SELECT sm FROM StockMovement sm WHERE sm.product.id = :productId ORDER BY sm.movementDate DESC")
    Page<StockMovement> findByProductIdOrderByDate(@Param("productId") Long productId, Pageable pageable);
    
    @Query("SELECT sm FROM StockMovement sm WHERE sm.product.id = :productId AND sm.movementDate BETWEEN :startDate AND :endDate")
    List<StockMovement> findByProductIdAndDateRange(
        @Param("productId") Long productId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
    
    @Query("SELECT sm FROM StockMovement sm WHERE sm.referenceType = :referenceType AND sm.referenceId = :referenceId")
    List<StockMovement> findByReference(@Param("referenceType") StockMovement.ReferenceType referenceType,
                                       @Param("referenceId") Long referenceId);

    // Global movement ledger with optional filters. Both joins are ManyToOne so
    // JOIN FETCH stays safe with pagination.
    @Query(value = """
        SELECT sm FROM StockMovement sm
        JOIN FETCH sm.product p
        LEFT JOIN FETCH sm.createdBy u
        WHERE (:productId IS NULL OR p.id = :productId)
          AND (:type IS NULL OR sm.movementType = :type)
          AND (:dateFrom IS NULL OR sm.movementDate >= :dateFrom)
          AND (:dateTo IS NULL OR sm.movementDate < :dateTo)
          AND (:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY sm.movementDate DESC, sm.id DESC
        """,
        countQuery = """
        SELECT COUNT(sm) FROM StockMovement sm
        JOIN sm.product p
        WHERE (:productId IS NULL OR p.id = :productId)
          AND (:type IS NULL OR sm.movementType = :type)
          AND (:dateFrom IS NULL OR sm.movementDate >= :dateFrom)
          AND (:dateTo IS NULL OR sm.movementDate < :dateTo)
          AND (:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<StockMovement> findFiltered(
            @Param("productId") Long productId,
            @Param("type") StockMovement.MovementType type,
            @Param("search") String search,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable);

    // Movement statistics source rows. Deliberately plain JPQL — this app runs
    // on both H2 (Electron/offline) and MySQL, so date-truncation functions
    // like DATE() are off-limits in native SQL here. Aggregation happens in
    // InventoryService.
    @Query("""
        SELECT sm.movementType, sm.quantity, sm.movementDate, sm.referenceType
        FROM StockMovement sm
        WHERE sm.movementDate >= :from
        """)
    List<Object[]> findMovementsSince(@Param("from") LocalDateTime from);
}
