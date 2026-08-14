package com.bms.repository;

import com.bms.entity.CashShift;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CashShiftRepository extends JpaRepository<CashShift, Long> {
    
    Optional<CashShift> findByCashierIdAndStatus(Long cashierId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cs FROM CashShift cs WHERE cs.id = :id")
    Optional<CashShift> findByIdForUpdate(@Param("id") Long id);

    boolean existsByCashierIdAndStatus(Long cashierId, String status);

    Page<CashShift> findByCashierId(Long cashierId, Pageable pageable);

    // Legacy methods (kept in case they are used elsewhere, but no longer used by getShiftHistory)
    @Query("SELECT cs FROM CashShift cs WHERE cs.cashierId IN :cashierIds AND cs.status = 'CLOSED' AND cs.closingTime BETWEEN :startDate AND :endDate")
    Page<CashShift> findClosedByCashierIdsAndDateRange(
        @Param("cashierIds") List<Long> cashierIds,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        Pageable pageable
    );

    @Query("SELECT cs FROM CashShift cs WHERE cs.status = 'CLOSED' AND cs.closingTime BETWEEN :startDate AND :endDate")
    Page<CashShift> findClosedByDateRange(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        Pageable pageable
    );

    // ✅ NEW methods used by getShiftHistory (Fix A2 & A3: includes OPEN shifts, filters by openingTime)
    @Query("SELECT cs FROM CashShift cs WHERE cs.cashierId IN :cashierIds AND cs.openingTime BETWEEN :startDate AND :endDate")
    Page<CashShift> findByCashierIdsAndDateRange(
        @Param("cashierIds") List<Long> cashierIds,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        Pageable pageable
    );

    @Query("SELECT cs FROM CashShift cs WHERE cs.openingTime BETWEEN :startDate AND :endDate")
    Page<CashShift> findByDateRange(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        Pageable pageable
    );
}