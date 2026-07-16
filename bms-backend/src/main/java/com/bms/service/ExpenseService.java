package com.bms.service;

import com.bms.entity.Expense;
import com.bms.entity.ExpenseCategory;
import com.bms.repository.ExpenseRepository;
import com.bms.dto.request.ExpenseCreateRequest;
import com.bms.dto.response.ExpenseResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class ExpenseService {

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private AuditLogService auditLogService;

    public Page<ExpenseResponse> getAllExpenses(Pageable pageable) {
        return expenseRepository.findAllActive(pageable).map(this::toResponse);
    }

    public Page<ExpenseResponse> getExpensesByCategory(ExpenseCategory category, Pageable pageable) {
        return expenseRepository.findByCategory(category, pageable).map(this::toResponse);
    }

    public Page<ExpenseResponse> getExpensesByDateRange(LocalDate startDate, LocalDate endDate, Pageable pageable) {
        return expenseRepository.findByDateRange(startDate, endDate, pageable).map(this::toResponse);
    }

    public Page<ExpenseResponse> getExpensesByCategoryAndDateRange(ExpenseCategory category, LocalDate startDate, LocalDate endDate, Pageable pageable) {
        return expenseRepository.findByCategoryAndDateRange(category, startDate, endDate, pageable).map(this::toResponse);
    }

    public ExpenseResponse getExpenseById(Long id) {
        Expense expense = expenseRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Expense not found with id: " + id));
        if (expense.getDeletedAt() != null || !expense.getIsActive()) {
            throw new RuntimeException("Expense not found or deleted");
        }
        return toResponse(expense);
    }

    public ExpenseResponse createExpense(ExpenseCreateRequest request, Long createdBy) {
        Expense expense = new Expense();
        expense.setCategory(ExpenseCategory.valueOf(request.getCategory()));
        expense.setDescription(request.getDescription());
        expense.setAmount(request.getAmount());
        expense.setExpenseDate(request.getExpenseDate());
        expense.setCreatedBy(createdBy);
        expense.setIsActive(true);

        Expense saved = expenseRepository.save(expense);
        
        auditLogService.log("EXPENSE_CREATED", 
            "Expense created: " + saved.getId() + " - " + saved.getCategory(), 
            createdBy);

        return toResponse(saved);
    }

    public ExpenseResponse updateExpense(Long id, ExpenseCreateRequest request, Long updatedBy) {
        Expense expense = expenseRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Expense not found with id: " + id));
        if (expense.getDeletedAt() != null || !expense.getIsActive()) {
            throw new RuntimeException("Expense not found or deleted");
        }

        expense.setCategory(ExpenseCategory.valueOf(request.getCategory()));
        expense.setDescription(request.getDescription());
        expense.setAmount(request.getAmount());
        expense.setExpenseDate(request.getExpenseDate());

        Expense updated = expenseRepository.save(expense);

        auditLogService.log("EXPENSE_UPDATED", 
            "Expense updated: " + updated.getId() + " - " + updated.getCategory(), 
            updatedBy);

        return toResponse(updated);
    }

    public void deleteExpense(Long id, Long deletedBy) {
        Expense expense = expenseRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Expense not found with id: " + id));
        if (expense.getDeletedAt() != null || !expense.getIsActive()) {
            throw new RuntimeException("Expense not found or deleted");
        }

        expense.setDeletedAt(java.time.LocalDateTime.now());
        expense.setIsActive(false);
        expenseRepository.save(expense);

        auditLogService.log("EXPENSE_DELETED", 
            "Expense deleted: " + id + " - " + expense.getCategory(), 
            deletedBy);
    }

    private ExpenseResponse toResponse(Expense expense) {
        ExpenseResponse response = new ExpenseResponse();
        response.setId(expense.getId());
        response.setCategory(expense.getCategory().name());
        response.setDescription(expense.getDescription());
        response.setAmount(expense.getAmount());
        response.setExpenseDate(expense.getExpenseDate());
        response.setCreatedBy(expense.getCreatedBy());
        response.setHasReceiptImage(expense.getReceiptImage() != null && expense.getReceiptImage().length > 0);
        response.setIsActive(expense.getIsActive());
        response.setCreatedAt(expense.getCreatedAt());
        response.setUpdatedAt(expense.getUpdatedAt());
        return response;
    }
}
