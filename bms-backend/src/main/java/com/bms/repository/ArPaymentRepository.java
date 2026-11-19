package com.bms.repository;

import com.bms.entity.ArPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
    // Reversed collections are excluded: voiding a collected sale pays the cash
    // back over the counter, so journaling the original debit would leave GL cash
    // above the drawer for good. Reversed payments are journaled separately via
    // findReversedForJournal.
    @EntityGraph(attributePaths = {"invoice", "recordedBy"})
    @Query("""
        SELECT p FROM ArPayment p
        WHERE p.paymentDate >= :startDate AND p.paymentDate < :endDate
          AND p.reversedAt IS NULL
        ORDER BY p.paymentDate ASC, p.id ASC
        """)
    List<ArPayment> findForJournal(@Param("startDate") LocalDateTime startDate,
                                   @Param("endDate") LocalDateTime endDate);

    // AR payments reversed within the period, for dated reversal journal entries
    // on the reversal date so closed periods do not change retrospectively.
    @EntityGraph(attributePaths = {"invoice", "recordedBy"})
    @Query("""
        SELECT p FROM ArPayment p
        WHERE p.reversedAt >= :startDate AND p.reversedAt < :endDate
        ORDER BY p.reversedAt ASC, p.id ASC
        """)
    List<ArPayment> findReversedForJournal(@Param("startDate") LocalDateTime startDate,
                                           @Param("endDate") LocalDateTime endDate);

    // What was collected on this invoice after the given row, for the balance shown
    // on a reprinted collection receipt. A reversed collection went straight back
    // over the counter, so it never reduced what the customer owed and does not count.
    @Query("""
        SELECT COALESCE(SUM(p.amount), 0) FROM ArPayment p
        WHERE p.invoice.id = :invoiceId AND p.id > :paymentId AND p.reversedAt IS NULL
        """)
    BigDecimal sumAmountByInvoiceIdAndIdGreaterThanExcludingReversed(
            @Param("invoiceId") Long invoiceId, @Param("paymentId") Long paymentId);
}