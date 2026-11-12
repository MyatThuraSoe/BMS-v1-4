package com.bms.repository;

import com.bms.entity.CashDrawerSession;
import com.bms.entity.DrawerSessionStatus;
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
public interface CashDrawerSessionRepository extends JpaRepository<CashDrawerSession, Long> {

    Optional<CashDrawerSession> findByDrawerIdAndStatus(Long drawerId, DrawerSessionStatus status);

    Optional<CashDrawerSession> findFirstByStatusOrderByOpenedAtDesc(DrawerSessionStatus status);

    List<CashDrawerSession> findByStatusOrderByOpenedAtAsc(DrawerSessionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CashDrawerSession s WHERE s.id = :id")
    Optional<CashDrawerSession> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT s FROM CashDrawerSession s WHERE s.openedAt BETWEEN :startDate AND :endDate ORDER BY s.openedAt DESC")
    Page<CashDrawerSession> findByDateRange(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        Pageable pageable
    );

    @Query("SELECT s FROM CashDrawerSession s WHERE s.status = 'OPEN' ORDER BY s.openedAt DESC")
    List<CashDrawerSession> findOpenSessions();

    @Query("SELECT s FROM CashDrawerSession s WHERE s.drawer.id = :drawerId AND s.status = 'OPEN' ORDER BY s.openedAt ASC")
    List<CashDrawerSession> findOpenSessionsForDrawer(@Param("drawerId") Long drawerId);

    /**
     * Sessions that overlap the report window. A drawer opened yesterday and
     * closed today must be reconciled in today's end-of-day report.
     */
    @Query("SELECT s FROM CashDrawerSession s WHERE s.openedAt <= :endDate " +
           "AND (s.closedAt IS NULL OR s.closedAt >= :startDate) ORDER BY s.openedAt ASC")
    List<CashDrawerSession> findForReport(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
}
