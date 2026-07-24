package com.bms.service;

import com.bms.dto.request.ExpenseRequest;
import com.bms.dto.response.ExpenseResponse;
import com.bms.entity.Expense;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.ExpenseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class ExpenseService {

    private final ExpenseRepository expenseRepository;

    public ExpenseService(ExpenseRepository expenseRepository) {
        this.expenseRepository = expenseRepository;
    }

    public List<ExpenseResponse> listExpenses(String category, LocalDate startDate, LocalDate endDate) {
        Expense.ExpenseCategory parsedCategory = null;
        if (category != null && !category.isBlank()) {
            try {
                parsedCategory = Expense.ExpenseCategory.valueOf(category.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("Invalid expense category: " + category);
            }
        }

        return expenseRepository.findFiltered(parsedCategory, startDate, endDate)
                .stream()
                .map(ExpenseResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public ExpenseResponse getExpense(Long id) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
        if (expense.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Expense not found: " + id);
        }
        return ExpenseResponse.fromEntity(expense);
    }

    public ExpenseResponse createExpense(ExpenseRequest request, Long userId) {
        Expense expense = new Expense();
        expense.setCategory(parseCategory(request.getCategory()));
        expense.setDescription(request.getDescription());
        expense.setAmount(request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO);
        expense.setExpenseDate(request.getExpenseDate());
        expense.setCreatedBy(userId);
        return ExpenseResponse.fromEntity(expenseRepository.save(expense));
    }

    public ExpenseResponse updateExpense(Long id, ExpenseRequest request, Long userId) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
        if (expense.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Expense not found: " + id);
        }

        expense.setCategory(parseCategory(request.getCategory()));
        expense.setDescription(request.getDescription());
        expense.setAmount(request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO);
        expense.setExpenseDate(request.getExpenseDate());
        expense.setCreatedBy(userId);
        return ExpenseResponse.fromEntity(expenseRepository.save(expense));
    }

    public void deleteExpense(Long id) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
        expense.setDeletedAt(java.time.LocalDateTime.now());
        expenseRepository.save(expense);
    }

    public void uploadReceiptImage(Long id, MultipartFile file) throws IOException {
        Expense expense = getActiveExpenseEntity(id);
        expense.setReceiptImage(file.getBytes());
        expense.setReceiptImageType(getFileExtension(file.getOriginalFilename()));
        expenseRepository.save(expense);
    }

    @Transactional(readOnly = true)
    public byte[] getReceiptImage(Long id) {
        Expense expense = getActiveExpenseEntity(id);
        if (expense.getReceiptImage() == null) {
            throw new ResourceNotFoundException("Expense " + id + " has no receipt image");
        }
        return expense.getReceiptImage();
    }

    @Transactional(readOnly = true)
    public String getReceiptImageType(Long id) {
        Expense expense = getActiveExpenseEntity(id);
        return expense.getReceiptImageType() != null ? expense.getReceiptImageType() : "jpeg";
    }

    public void deleteReceiptImage(Long id) {
        Expense expense = getActiveExpenseEntity(id);
        expense.setReceiptImage(null);
        expense.setReceiptImageType(null);
        expenseRepository.save(expense);
    }

    private Expense.ExpenseCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new BusinessException("Category is required");
        }
        try {
            return Expense.ExpenseCategory.valueOf(category.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid expense category: " + category);
        }
    }

    private Expense getActiveExpenseEntity(Long id) {
        Expense expense = expenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
        if (expense.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Expense not found: " + id);
        }
        return expense;
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "jpeg";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}
