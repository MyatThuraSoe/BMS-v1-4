package com.bms.repository;

import com.bms.entity.Expense;
import com.bms.entity.ExpenseCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    @Query("SELECT e FROM Expense e WHERE e.isActive = true AND e.deletedAt IS NULL ORDER BY e.expenseDate DESC")
    Page<Expense> findAllActive(Pageable pageable);

    @Query("SELECT e FROM Expense e WHERE e.isActive = true AND e.deletedAt IS NULL AND e.category = :category ORDER BY e.expenseDate DESC")
    Page<Expense> findByCategory(@Param("category") ExpenseCategory category, Pageable pageable);

    @Query("SELECT e FROM Expense e WHERE e.isActive = true AND e.deletedAt IS NULL AND e.expenseDate BETWEEN :startDate AND :endDate ORDER BY e.expenseDate DESC")
    Page<Expense> findByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, Pageable pageable);

    @Query("SELECT e FROM Expense e WHERE e.isActive = true AND e.deletedAt IS NULL AND e.category = :category AND e.expenseDate BETWEEN :startDate AND :endDate ORDER BY e.expenseDate DESC")
    Page<Expense> findByCategoryAndDateRange(@Param("category") ExpenseCategory category, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate, Pageable pageable);

    @Query("SELECT SUM(e.amount) FROM Expense e WHERE e.isActive = true AND e.deletedAt IS NULL AND e.expenseDate BETWEEN :startDate AND :endDate")
    BigDecimal sumByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT e.category, SUM(e.amount) FROM Expense e WHERE e.isActive = true AND e.deletedAt IS NULL AND e.expenseDate BETWEEN :startDate AND :endDate GROUP BY e.category")
    List<Object[]> sumByCategoryAndDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
