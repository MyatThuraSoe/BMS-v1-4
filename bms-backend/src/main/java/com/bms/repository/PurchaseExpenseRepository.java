package com.bms.repository;

import com.bms.entity.PurchaseExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface PurchaseExpenseRepository extends JpaRepository<PurchaseExpense, Long> {
    List<PurchaseExpense> findByPurchaseIdOrderByIdAsc(Long purchaseId);
    void deleteByPurchaseId(Long purchaseId);

    /**
     * O4: purchase-capitalised bills with the same amount dated within a few
     * days of an expense — candidates for a double-counted freight bill.
     */
    @Query("""
        SELECT pe FROM PurchaseExpense pe
        JOIN FETCH pe.purchase p
        WHERE pe.amount = :amount
          AND p.isActive = true AND p.deletedAt IS NULL
          AND p.purchaseDate BETWEEN :startDate AND :endDate
        ORDER BY p.purchaseDate ASC, pe.id ASC
        """)
    List<PurchaseExpense> findPotentialDuplicates(@Param("amount") BigDecimal amount,
                                                  @Param("startDate") LocalDate startDate,
                                                  @Param("endDate") LocalDate endDate);
}
