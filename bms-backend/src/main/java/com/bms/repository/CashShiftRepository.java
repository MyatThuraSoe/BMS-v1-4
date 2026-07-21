package com.bms.repository;

import com.bms.entity.CashShift;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface CashShiftRepository extends JpaRepository<CashShift, Long> {
    
    @Query("SELECT cs FROM CashShift cs WHERE cs.cashierId = :cashierId AND cs.status = 'OPEN'")
    Optional<CashShift> findByCashierIdAndStatus(@Param("cashierId") Long cashierId, @Param("status") String status);
    
    boolean existsByCashierIdAndStatus(@Param("cashierId") Long cashierId, @Param("status") String status);
    
    @Query("SELECT cs FROM CashShift cs WHERE cs.status = 'OPEN' ORDER BY cs.openingTime DESC")
    Page<CashShift> findOpenShifts(Pageable pageable);
    
    @Query("SELECT cs FROM CashShift cs WHERE cs.status = 'CLOSED' ORDER BY cs.closingTime DESC")
    Page<CashShift> findClosedShifts(Pageable pageable);
    
    @Query("SELECT cs FROM CashShift cs " +
           "WHERE (:cashierId IS NULL OR cs.cashierId = :cashierId) " +
           "AND (:startDate IS NULL OR cs.openingTime >= :startDate) " +
           "AND (:endDate IS NULL OR cs.openingTime <= :endDate) " +
           "ORDER BY cs.openingTime DESC")
    Page<CashShift> findByFilters(
        @Param("cashierId") Long cashierId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        Pageable pageable);
}
