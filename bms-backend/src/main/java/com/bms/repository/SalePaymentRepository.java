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

    List<SalePayment> findBySaleIdOrderByPaidAtAsc(Long saleId);

    List<SalePayment> findBySaleIdInOrderByPaidAtAsc(Collection<Long> saleIds);

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
