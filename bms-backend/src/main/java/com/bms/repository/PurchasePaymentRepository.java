package com.bms.repository;

import com.bms.entity.PurchasePayment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface PurchasePaymentRepository extends JpaRepository<PurchasePayment, Long> {

    @EntityGraph(attributePaths = {"purchase", "recordedBy"})
    List<PurchasePayment> findByPurchaseIdOrderByPaymentDateAsc(@Param("purchaseId") Long purchaseId);

    // Payments dated in the half-open period [startDate, endDate) for the GL
    // journal. The linked drawer PAY_OUT is the duplicate cash trail and is
    // skipped by the drawer posting.
    @EntityGraph(attributePaths = {"purchase", "recordedBy"})
    @Query("""
        SELECT p FROM PurchasePayment p
        WHERE p.paymentDate >= :startDate AND p.paymentDate < :endDate
        ORDER BY p.paymentDate ASC, p.id ASC
        """)
    List<PurchasePayment> findForJournal(@Param("startDate") LocalDateTime startDate,
                                         @Param("endDate") LocalDateTime endDate);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PurchasePayment p WHERE p.purchase.id = :purchaseId")
    BigDecimal sumAmountByPurchaseId(@Param("purchaseId") Long purchaseId);

    @Query("SELECT p.purchase.id, COALESCE(SUM(p.amount), 0) FROM PurchasePayment p "
            + "WHERE p.purchase.id IN :purchaseIds GROUP BY p.purchase.id")
    List<Object[]> sumAmountsByPurchaseIds(@Param("purchaseIds") Collection<Long> purchaseIds);
}