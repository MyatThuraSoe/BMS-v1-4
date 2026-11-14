package com.bms.repository;

import com.bms.entity.PaymentMethod;
import com.bms.entity.SalePayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Repository
public interface SalePaymentRepository extends JpaRepository<SalePayment, Long> {

    // Method names kept for existing callers; explicit queries add the id
    // tiebreaker so same-timestamp payments always replay in insertion order.
    @Query("SELECT p FROM SalePayment p WHERE p.sale.id = :saleId ORDER BY p.paidAt ASC, p.id ASC")
    List<SalePayment> findBySaleIdOrderByPaidAtAsc(@Param("saleId") Long saleId);

    @Query("SELECT p FROM SalePayment p WHERE p.sale.id IN :saleIds ORDER BY p.paidAt ASC, p.id ASC")
    List<SalePayment> findBySaleIdInOrderByPaidAtAsc(@Param("saleIds") Collection<Long> saleIds);

    @Query("SELECT p.paymentMethod, COALESCE(SUM(p.amount), 0) FROM SalePayment p " +
           "WHERE p.sale.id = :saleId GROUP BY p.paymentMethod")
    List<Object[]> summariseBySale(@Param("saleId") Long saleId);

    @Query("SELECT p.paymentMethod, COALESCE(SUM(p.amount), 0), COUNT(p) FROM SalePayment p " +
           "WHERE p.sale.employeeShift.id = :shiftId AND p.sale.isVoided = false " +
           "GROUP BY p.paymentMethod")
    List<Object[]> summariseByEmployeeShift(@Param("shiftId") Long shiftId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM SalePayment p " +
           "WHERE p.sale.employeeShift.id = :shiftId AND p.paymentMethod = :method " +
           "AND p.sale.isVoided = false")
    BigDecimal sumByEmployeeShiftAndMethod(
        @Param("shiftId") Long shiftId,
        @Param("method") PaymentMethod method);
}
