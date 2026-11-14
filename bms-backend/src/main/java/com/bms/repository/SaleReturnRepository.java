package com.bms.repository;

import com.bms.entity.SaleReturn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface SaleReturnRepository extends JpaRepository<SaleReturn, Long> {
    List<SaleReturn> findByReturnDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    // Half-open [startDate, endDate) returns for the GL journal (P4.5).
    @Query("""
        SELECT r FROM SaleReturn r
        WHERE r.returnDate >= :startDate AND r.returnDate < :endDate
        ORDER BY r.returnDate ASC, r.id ASC
        """)
    List<SaleReturn> findForJournal(@Param("startDate") LocalDateTime startDate,
                                    @Param("endDate") LocalDateTime endDate);

    // Method names kept for existing callers; explicit queries add the id
    // tiebreaker so same-timestamp returns always list in a stable order.
    @Query("SELECT r FROM SaleReturn r WHERE r.sale.id = :saleId ORDER BY r.returnDate DESC, r.id DESC")
    List<SaleReturn> findBySaleIdOrderByReturnDateDesc(@Param("saleId") Long saleId);

    @Query("SELECT r FROM SaleReturn r WHERE r.sale.id IN :saleIds ORDER BY r.returnDate DESC, r.id DESC")
    List<SaleReturn> findBySaleIdInOrderByReturnDateDesc(@Param("saleIds") Collection<Long> saleIds);

    @Query("""
        SELECT r FROM SaleReturn r
        WHERE (:saleId IS NULL OR r.sale.id = :saleId)
          AND (:invoice IS NULL OR :invoice = ''
               OR LOWER(r.sale.invoiceNumber) LIKE LOWER(CONCAT('%', :invoice, '%')))
        """)
    Page<SaleReturn> findFilteredReturns(@Param("saleId") Long saleId,
                                         @Param("invoice") String invoice,
                                         Pageable pageable);

    @Query("SELECT COALESCE(SUM(r.totalReturnAmount), 0) FROM SaleReturn r WHERE r.employeeShift.id = :shiftId")
    java.math.BigDecimal sumTotalByEmployeeShiftId(@Param("shiftId") Long shiftId);

    @Query("SELECT COALESCE(SUM(r.totalReturnAmount), 0) FROM SaleReturn r " +
           "WHERE r.sale.id = :saleId AND r.refundMethod = com.bms.entity.PaymentMethod.CASH")
    java.math.BigDecimal sumCashRefundedBySaleId(@Param("saleId") Long saleId);
}
