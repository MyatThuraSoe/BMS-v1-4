package com.bms.repository;

import com.bms.entity.Refund;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {
    
    @Query("SELECT r FROM Refund r WHERE r.isActive = true AND r.deletedAt IS NULL AND " +
           "r.sale.id = :saleId ORDER BY r.refundDate DESC")
    List<Refund> findBySaleId(@Param("saleId") Long saleId);
    
    @Query("SELECT r FROM Refund r WHERE r.isActive = true AND r.deletedAt IS NULL AND " +
           "r.refundedBy.id = :userId ORDER BY r.refundDate DESC")
    Page<Refund> findByRefundedBy(@Param("userId") Long userId, Pageable pageable);
    
    @Query("SELECT r FROM Refund r WHERE r.isActive = true AND r.deletedAt IS NULL AND " +
           "r.refundDate BETWEEN :startDate AND :endDate ORDER BY r.refundDate DESC")
    Page<Refund> findByDateRange(@Param("startDate") LocalDateTime startDate, 
                                 @Param("endDate") LocalDateTime endDate, 
                                 Pageable pageable);
}
