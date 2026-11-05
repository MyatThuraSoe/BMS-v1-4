package com.bms.repository;

import com.bms.entity.CashDrawerMovement;
import com.bms.entity.DrawerMovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CashDrawerMovementRepository extends JpaRepository<CashDrawerMovement, Long> {

    List<CashDrawerMovement> findByDrawerSessionIdOrderByOccurredAtAsc(Long drawerSessionId);

    List<CashDrawerMovement> findByDrawerSessionIdAndMovementTypeOrderByOccurredAtAsc(
        Long drawerSessionId, DrawerMovementType movementType);

    @Query("SELECT COALESCE(SUM(m.amount), 0) FROM CashDrawerMovement m WHERE m.drawerSession.id = :sessionId")
    BigDecimal sumAmountBySession(@Param("sessionId") Long sessionId);

    @Query("SELECT COALESCE(SUM(m.amount), 0) FROM CashDrawerMovement m " +
           "WHERE m.drawerSession.id = :sessionId AND m.movementType = :type")
    BigDecimal sumAmountBySessionAndType(
        @Param("sessionId") Long sessionId,
        @Param("type") DrawerMovementType type);

    @Query("SELECT COALESCE(SUM(m.amount), 0) FROM CashDrawerMovement m " +
           "WHERE m.employeeShift.id = :shiftId AND m.movementType = :type")
    BigDecimal sumAmountByEmployeeShiftAndType(
        @Param("shiftId") Long shiftId,
        @Param("type") DrawerMovementType type);

    @Query("SELECT m.movementType, COALESCE(SUM(m.amount), 0), COUNT(m) " +
           "FROM CashDrawerMovement m WHERE m.drawerSession.id = :sessionId GROUP BY m.movementType")
    List<Object[]> summariseByType(@Param("sessionId") Long sessionId);

    @Query("SELECT m.employeeShift.id, m.employeeShift.cashierId, m.movementType, COALESCE(SUM(m.amount), 0) " +
           "FROM CashDrawerMovement m " +
           "WHERE m.drawerSession.id = :sessionId AND m.employeeShift IS NOT NULL " +
           "AND m.movementType IN (com.bms.entity.DrawerMovementType.CASH_SALE, com.bms.entity.DrawerMovementType.CASH_REFUND) " +
           "GROUP BY m.employeeShift.id, m.employeeShift.cashierId, m.movementType")
    List<Object[]> summariseByEmployee(@Param("sessionId") Long sessionId);

    @Query("SELECT m FROM CashDrawerMovement m WHERE m.occurredAt BETWEEN :startDate AND :endDate " +
           "AND (:type IS NULL OR m.movementType = :type) ORDER BY m.occurredAt DESC")
    List<CashDrawerMovement> findForReport(
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate,
        @Param("type") DrawerMovementType type);
}
