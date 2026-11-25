package com.bms.service;

import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.CashDrawer;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.Expense;
import com.bms.entity.Role;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Audit item 12: a cash-paid expense must clear Expense Payable and its linked
 * drawer pay-out must not accumulate in Cash Clearing.
 */
@DataJpaTest
@ContextConfiguration(classes = ExpenseLedgerSettlementTest.JpaConfig.class)
@Import({GlService.class, LedgerPostingService.class, LedgerHistoryService.class, PeriodGuard.class,
        LedgerReadService.class, InventoryLossService.class, ReportService.class, ShopTimeService.class})
class ExpenseLedgerSettlementTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private GlService glService;
    @Autowired private LedgerPostingService postingService;

    @MockBean private AuditLogService auditLogService;
    @MockBean private CashShiftService cashShiftService;
    @MockBean private CashDrawerSessionService cashDrawerSessionService;
    @MockBean private SystemSettingService systemSettingService;

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 30);

    private User user;
    private CashDrawerSession session;

    @BeforeEach
    void seedShop() {
        glService.ensureDefaults();
        em.persist(setting(LedgerReadService.CUTOVER_KEY, "2026-11-01T00:00:00"));
        em.persist(setting(PeriodGuard.LOCK_KEY, "0"));

        Role role = new Role();
        role.setName(Role.RoleName.ROLE_ADMIN);
        em.persist(role);
        user = new User();
        user.setUsername("expense-fixture");
        user.setEmail("expense-fixture@example.test");
        user.setPassword("unused-fixture-password");
        user.setFirstName("Expense");
        user.setLastName("Fixture");
        user.setRole(role);
        em.persist(user);

        CashDrawer drawer = new CashDrawer();
        drawer.setName("Expense drawer");
        drawer.setCode("EXP-DRW");
        em.persist(drawer);
        session = new CashDrawerSession();
        session.setDrawer(drawer);
        session.setOpenedBy(user);
        session.setOpeningFloat(new BigDecimal("100.00"));
        session.setOpenedAt(LocalDateTime.of(2026, 11, 1, 8, 0));
        em.persistAndFlush(session);
    }

    @Test
    void paidCashExpenseClearsThePayableAndSkipsTheDrawerClearingLeg() {
        Expense expense = new Expense();
        expense.setCategory(Expense.ExpenseCategory.RENT);
        expense.setDescription("November rent");
        expense.setAmount(new BigDecimal("50.00"));
        expense.setExpenseDate(LocalDate.of(2026, 11, 10));
        expense.setCreatedBy(user.getId());
        expense.setSettled(true);
        expense = em.persistAndFlush(expense);
        postingService.postExpense(expense);

        CashDrawerMovement movement = new CashDrawerMovement();
        movement.setDrawerSession(session);
        movement.setMovementType(DrawerMovementType.PAY_OUT);
        movement.setAmount(new BigDecimal("-50.00"));
        movement.setReferenceType("EXPENSE");
        movement.setReferenceId(expense.getId());
        movement.setPerformedBy(user);
        movement.setReason("November rent paid in cash");
        movement.setOccurredAt(LocalDateTime.of(2026, 11, 10, 9, 0));
        em.persistAndFlush(movement);

        List<JournalEntryResponse> entries = glService.journal(START, END);
        assertFalse(entries.isEmpty());

        assertEquals(0, netDebit(entries, "OPERATING_EXPENSE").compareTo(new BigDecimal("50.00")),
                "expense must hit the P&L: " + entries);
        assertEquals(0, netDebit(entries, "EXPENSE_PAYABLE").compareTo(BigDecimal.ZERO),
                "a paid expense must not leave a payable: " + entries);
        assertEquals(0, netDebit(entries, "CASH").compareTo(new BigDecimal("-50.00")),
                "cash paid out must be credited: " + entries);
        assertEquals(0, netDebit(entries, "CASH_CLEARING").compareTo(BigDecimal.ZERO),
                "the linked drawer movement must not post a second cash leg: " + entries);
        assertEquals(0, netDebits(entries).compareTo(netCredits(entries)), "journal must balance");
    }

    private static SystemSetting setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setDescription("test fixture");
        return setting;
    }

    private static BigDecimal netDebit(List<JournalEntryResponse> entries, String account) {
        return entries.stream().filter(e -> account.equals(e.getEventType()))
                .map(e -> e.getDebit().subtract(e.getCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal netDebits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal netCredits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
