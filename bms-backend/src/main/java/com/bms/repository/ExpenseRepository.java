package com.bms.repository;

import com.bms.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Expense e WHERE e.id = :id")
    java.util.Optional<Expense> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT e FROM Expense e WHERE e.deletedAt IS NULL AND (:category IS NULL OR e.category = :category) " +
            "AND (:startDate IS NULL OR e.expenseDate >= :startDate) " +
            "AND (:endDate IS NULL OR e.expenseDate <= :endDate) ORDER BY e.expenseDate DESC")
    List<Expense> findFiltered(@Param("category") Expense.ExpenseCategory category,
                               @Param("startDate") LocalDate startDate,
                               @Param("endDate") LocalDate endDate);

    @Query("SELECT e FROM Expense e WHERE e.sourcePurchaseId = :sourcePurchaseId AND e.deletedAt IS NULL")
    java.util.List<Expense> findBySourcePurchaseId(@Param("sourcePurchaseId") Long sourcePurchaseId);
}
