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
}
