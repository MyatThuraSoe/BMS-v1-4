package com.bms.repository;

import com.bms.entity.ArPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ArPaymentRepository extends JpaRepository<ArPayment, Long> {

    // Method name kept for existing callers; explicit query adds the id
    // tiebreaker so same-date collections always list in insertion order.
    @EntityGraph(attributePaths = {"invoice", "recordedBy"})
    @Query("SELECT p FROM ArPayment p WHERE p.invoice.id = :invoiceId ORDER BY p.paymentDate ASC, p.id ASC")
    List<ArPayment> findByInvoiceIdOrderByPaymentDateAsc(@Param("invoiceId") Long invoiceId);

    // Collections dated in the half-open period [startDate, endDate) for the GL
    // journal (P4.5). The linked drawer PAY_IN is the duplicate cash trail.
    @EntityGraph(attributePaths = {"invoice", "recordedBy"})
    @Query("""
        SELECT p FROM ArPayment p
        WHERE p.paymentDate >= :startDate AND p.paymentDate < :endDate
        ORDER BY p.paymentDate ASC, p.id ASC
        """)
    List<ArPayment> findForJournal(@Param("startDate") LocalDateTime startDate,
                                   @Param("endDate") LocalDateTime endDate);
}