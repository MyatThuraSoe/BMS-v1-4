package com.bms.service;

import com.bms.dto.request.ExpenseRequest;
import com.bms.dto.response.ExpenseResponse;
import com.bms.entity.Expense;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.ExpenseRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private ShopTimeService shopTimeService;

    /** O4: +/- days around the expense date scanned for duplicate freight bills. */
    private static final int DUPLICATE_WINDOW_DAYS = 3;

    private final ExpenseRepository expenseRepository;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private PeriodGuard periodGuard;

    @Autowired
    private com.bms.repository.PurchaseExpenseRepository purchaseExpenseRepository;

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
        periodGuard.assertOpen(request.getExpenseDate());
        Expense expense = new Expense();
        expense.setCategory(parseCategory(request.getCategory()));
        expense.setDescription(request.getDescription());
        expense.setAmount(requirePositive(request.getAmount()));
        expense.setExpenseDate(request.getExpenseDate());
        expense.setCreatedBy(userId);
        applyCapitalization(expense, request);
        expenseRepository.save(expense);
        ledgerPostingService.postExpense(expense);
        ExpenseResponse response = ExpenseResponse.fromEntity(expense);
        // O4: non-blocking duplicate hints against purchase-capitalised bills.
        response.setDuplicateWarnings(findDuplicateWarnings(expense));
        return response;
    }

    public ExpenseResponse updateExpense(Long id, ExpenseRequest request, Long userId) {
        periodGuard.lock();
        Expense expense = expenseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
        if (expense.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Expense not found: " + id);
        }

        periodGuard.assertOpen(expense.getExpenseDate());
        periodGuard.assertOpen(request.getExpenseDate());
        Expense.ExpenseCategory category = parseCategory(request.getCategory());
        BigDecimal amount = requirePositive(request.getAmount());
        ledgerPostingService.postExpense(expense);
        ledgerPostingService.reverseExpense(expense, expense.getExpenseDate().atStartOfDay());
        expense.setLedgerRevision(expense.getLedgerRevision() + 1);
        expense.setCategory(category);
        expense.setDescription(request.getDescription());
        expense.setAmount(amount);
        expense.setExpenseDate(request.getExpenseDate());
        expense.setUpdatedBy(userId);
        expense.setUpdatedAt(shopTimeService.now());
        // O4: capitalization changes after the old-state reversal so toggling
        // the flag cleanly moves the cost in/out of the P&L.
        applyCapitalization(expense, request);
        expenseRepository.save(expense);
        ledgerPostingService.postExpense(expense);
        return ExpenseResponse.fromEntity(expense);
    }

    /**
     * O4: an expense linked to a purchase_expenses row is always capitalised;
     * the flag alone also marks costs already absorbed into inventory.
     */
    private void applyCapitalization(Expense expense, ExpenseRequest request) {
        Long purchaseExpenseId = request.getPurchaseExpenseId();
        if (purchaseExpenseId != null) {
            if (!purchaseExpenseRepository.existsById(purchaseExpenseId)) {
                throw new ResourceNotFoundException("Purchase expense not found: " + purchaseExpenseId);
            }
            expense.setPurchaseExpenseId(purchaseExpenseId);
            expense.setCapitalized(true);
        } else {
            expense.setPurchaseExpenseId(null);
            expense.setCapitalized(Boolean.TRUE.equals(request.getCapitalized()));
        }
    }

    /**
     * O4: warns (never blocks) when the amount matches a purchase-capitalised
     * bill dated within a few days — the classic double-counted freight case.
     */
    private List<String> findDuplicateWarnings(Expense expense) {
        List<String> warnings = new java.util.ArrayList<>();
        if (expense.getAmount() == null || expense.getExpenseDate() == null) {
            return warnings;
        }
        List<com.bms.entity.PurchaseExpense> candidates = purchaseExpenseRepository.findPotentialDuplicates(
                expense.getAmount().setScale(2, java.math.RoundingMode.HALF_UP),
                expense.getExpenseDate().minusDays(DUPLICATE_WINDOW_DAYS),
                expense.getExpenseDate().plusDays(DUPLICATE_WINDOW_DAYS));
        for (com.bms.entity.PurchaseExpense candidate : candidates) {
            com.bms.entity.Purchase purchase = candidate.getPurchase();
            String supplier = purchase.getSupplier() != null ? purchase.getSupplier().getName() : "Walk-in";
            warnings.add("Possible duplicate: " + expense.getAmount().toPlainString()
                    + " matches " + candidate.getType().name().toLowerCase() + " bill \""
                    + candidate.getDescription() + "\" on purchase " + purchase.getPurchaseNumber()
                    + " (" + supplier + ", " + purchase.getPurchaseDate() + ")."
                    + " If this cost was already capitalised on the purchase, mark the expense as"
                    + " capitalized to keep it out of the P&L.");
        }
        return warnings;
    }

    private BigDecimal requirePositive(BigDecimal amount) {
        BigDecimal value = amount != null ? amount : BigDecimal.ZERO;
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Expense amount cannot be negative");
        }
        return value;
    }

    public void deleteExpense(Long id) {
        periodGuard.lock();
        Expense expense = expenseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + id));
        if (expense.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Expense not found: " + id);
        }
        ledgerPostingService.postExpense(expense);
        ledgerPostingService.reverseExpense(expense, expense.getExpenseDate().atStartOfDay());
        expense.setDeletedAt(shopTimeService.now());
        expenseRepository.save(expense);
    }

    public void uploadReceiptImage(Long id, MultipartFile file) throws IOException {
        // Trust magic bytes, never the client Content-Type / filename extension.
        String mime = com.bms.util.ImageValidationUtil.validateImage(file);
        Expense expense = getActiveExpenseEntity(id);
        expense.setReceiptImage(file.getBytes());
        expense.setReceiptImageType(com.bms.util.ImageValidationUtil.mimeToExtension(mime));
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
}
