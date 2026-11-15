package com.bms.service;

import com.bms.dto.request.ExpenseRequest;
import com.bms.dto.response.ExpenseResponse;
import com.bms.entity.Expense;
import com.bms.repository.ExpenseRepository;
import com.bms.repository.PurchaseExpenseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExpenseServiceCreatorAuditTest {

    @Test
    void updatePreservesCreatorAndRecordsEditor() {
        ExpenseRepository expenseRepository = mock(ExpenseRepository.class);
        Expense expense = new Expense();
        expense.setId(4L);
        expense.setCreatedBy(11L);
        expense.setCategory(Expense.ExpenseCategory.OTHER);
        expense.setDescription("Original");
        expense.setAmount(new BigDecimal("10.00"));
        expense.setExpenseDate(LocalDate.of(2026, 1, 1));
        when(expenseRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(expense));
        when(expenseRepository.save(expense)).thenReturn(expense);

        ExpenseService service = new ExpenseService(expenseRepository);
        ReflectionTestUtils.setField(service, "periodGuard", mock(PeriodGuard.class));
        ReflectionTestUtils.setField(service, "ledgerPostingService", mock(LedgerPostingService.class));
        ReflectionTestUtils.setField(service, "purchaseExpenseRepository", mock(PurchaseExpenseRepository.class));

        ExpenseRequest request = new ExpenseRequest();
        request.setCategory("OTHER");
        request.setDescription("Updated");
        request.setAmount(new BigDecimal("12.00"));
        request.setExpenseDate(LocalDate.of(2026, 1, 2));
        ExpenseResponse response = service.updateExpense(4L, request, 22L);

        assertEquals(11L, response.getCreatedBy());
        assertEquals(22L, response.getUpdatedBy());
        assertEquals("Updated", response.getDescription());
    }
}
