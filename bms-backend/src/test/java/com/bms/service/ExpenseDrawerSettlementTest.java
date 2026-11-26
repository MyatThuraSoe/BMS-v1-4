package com.bms.service;

import com.bms.dto.request.ExpenseRequest;
import com.bms.entity.CashDrawer;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.Expense;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.ExpenseRepository;
import com.bms.repository.PurchaseExpenseRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit T6-7: a cash expense paid from the till must be marked settled and
 * must write the PAY_OUT that keeps expected drawer cash honest.
 */
class ExpenseDrawerSettlementTest {

    private static final Long USER_ID = 7L;

    private ExpenseRepository expenseRepository;
    private CashDrawerSessionService drawerSessionService;
    private PaymentValidationService paymentValidationService;
    private UserRepository userRepository;
    private ExpenseService service;
    private CashDrawerSession session;
    private CashShift shift;
    private User user;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ExpenseRepository.class);
        when(expenseRepository.save(any(Expense.class))).thenAnswer(invocation -> {
            Expense expense = invocation.getArgument(0);
            if (expense.getId() == null) {
                expense.setId(91L);
            }
            return expense;
        });

        drawerSessionService = mock(CashDrawerSessionService.class);
        paymentValidationService = mock(PaymentValidationService.class);
        userRepository = mock(UserRepository.class);

        user = new User();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        CashDrawer drawer = new CashDrawer();
        drawer.setId(3L);
        session = new CashDrawerSession();
        session.setId(80L);
        session.setDrawer(drawer);
        shift = new CashShift();
        shift.setId(70L);
        shift.setDrawerSession(session);
        when(paymentValidationService.findOpenShift(USER_ID)).thenReturn(shift);

        service = new ExpenseService(expenseRepository);
        ShopTimeTestSupport.withClock(service);
        ReflectionTestUtils.setField(service, "periodGuard", mock(PeriodGuard.class));
        ReflectionTestUtils.setField(service, "ledgerPostingService", mock(LedgerPostingService.class));
        ReflectionTestUtils.setField(service, "purchaseExpenseRepository", mock(PurchaseExpenseRepository.class));
        ReflectionTestUtils.setField(service, "paymentValidationService", paymentValidationService);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "cashDrawerSessionService", drawerSessionService);
    }

    @Test
    void cashExpensePaidFromDrawerIsSettledAndRecordedAsPayOut() {
        service.createExpense(request(true), USER_ID);

        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(saved.capture());
        assertEquals(Boolean.TRUE, saved.getValue().getSettled());

        verify(drawerSessionService).recordFinancialMovement(
                eq(session), eq(DrawerMovementType.PAY_OUT), eq(new BigDecimal("-40.00")),
                eq("EXPENSE"), eq(91L), eq(user), eq(shift), anyString());
    }

    @Test
    void accrualExpenseDoesNotTouchTheDrawer() {
        service.createExpense(request(false), USER_ID);

        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(saved.capture());
        assertEquals(Boolean.FALSE, saved.getValue().getSettled());
        verify(drawerSessionService, never()).recordFinancialMovement(
                any(), any(), any(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void drawerSettlementWithoutAnOpenShiftIsRejected() {
        when(paymentValidationService.findOpenShift(USER_ID)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.createExpense(request(true), USER_ID));
        verify(drawerSessionService, never()).recordFinancialMovement(
                any(), any(), any(), anyString(), any(), any(), any(), anyString());
    }

    private static ExpenseRequest request(boolean paidFromDrawer) {
        ExpenseRequest request = new ExpenseRequest();
        request.setCategory("SUPPLIES");
        request.setDescription("Office supplies");
        request.setAmount(new BigDecimal("40.00"));
        request.setExpenseDate(LocalDate.now().minusDays(1));
        ReflectionTestUtils.setField(request, "paidFromDrawer", paidFromDrawer);
        return request;
    }
}
