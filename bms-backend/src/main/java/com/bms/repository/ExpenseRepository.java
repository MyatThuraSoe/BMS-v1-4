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

    @Query("SELECT e FROM Expense e WHERE e.deletedAt IS NULL AND (:category IS NULL OR e.category = :category) " +
            "AND (:startDate IS NULL OR e.expenseDate >= :startDate) " +
            "AND (:endDate IS NULL OR e.expenseDate <= :endDate) ORDER BY e.expenseDate DESC")
    List<Expense> findFiltered(@Param("category") Expense.ExpenseCategory category,
                               @Param("startDate") LocalDate startDate,
                               @Param("endDate") LocalDate endDate);
}
