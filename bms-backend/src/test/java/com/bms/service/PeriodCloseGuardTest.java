package com.bms.service;

import com.bms.entity.CashDrawer;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.PeriodClose;
import com.bms.entity.SystemSetting;
import com.bms.exception.BusinessException;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1: closing a month freezes it, so the two shop switches have to be honoured —
 * a grace window after month end for late paperwork, and a refusal to freeze a
 * month that still has a drawer open inside it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PeriodCloseGuardTest {

    private static final Long USER_ID = 4L;

    @Mock private PeriodGuard periodGuard;
    @Mock private PeriodCloseRepository periodCloseRepository;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private LedgerHistoryService historyService;
    @Mock private LedgerReadService readService;
    @Mock private AuditLogService auditLogService;
    @Mock private InventoryHistoryService inventoryHistoryService;
    @Mock private CashDrawerSessionRepository drawerSessionRepository;

    private AccountingPeriodService periodService;
    private YearMonth lastMonth;

    @BeforeEach
    void setUp() {
        periodService = new AccountingPeriodService(periodGuard, periodCloseRepository,
                systemSettingRepository, historyService, readService, auditLogService);
        ReflectionTestUtils.setField(periodService, "inventoryHistoryService", inventoryHistoryService);
        ReflectionTestUtils.setField(periodService, "drawerSessionRepository", drawerSessionRepository);
        ShopTimeTestSupport.withClock(periodService);

        lastMonth = YearMonth.from(LocalDate.now()).minusMonths(1);
        lenient().when(readService.isActive()).thenReturn(true);
        lenient().when(periodCloseRepository.existsById(any())).thenReturn(false);
        lenient().when(historyService.reconcile(any(), any()))
                .thenReturn(Map.of("reconciled", true));
        lenient().when(inventoryHistoryService.reconcile()).thenReturn(Map.of("reconciled", true));
        lenient().when(readService.totals(any(), any())).thenReturn(new BigDecimal[]{
                new BigDecimal("100.00"), new BigDecimal("40.00"), new BigDecimal("5.00")});
        lenient().when(periodCloseRepository.save(any(PeriodClose.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(drawerSessionRepository.findOpenSessionsOpenedIn(any(), any()))
                .thenReturn(List.of());
    }

    private void setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        when(systemSettingRepository.findBySettingKey(key)).thenReturn(Optional.of(setting));
    }

    private CashDrawerSession openSession(long id, String drawerName) {
        CashDrawer drawer = new CashDrawer();
        drawer.setId(id);
        drawer.setName(drawerName);

        CashDrawerSession session = new CashDrawerSession();
        session.setId(id);
        session.setDrawer(drawer);
        session.setStatus(DrawerSessionStatus.OPEN);
        session.setOpenedAt(lastMonth.atDay(9).atTime(8, 30));
        return session;
    }

    @Test
    void anOpenDrawerInTheMonthBlocksTheCloseAndNamesTheSession() {
        when(drawerSessionRepository.findForReport(any(), any()))
                .thenReturn(List.of(openSession(12L, "Front counter")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> periodService.close(lastMonth, USER_ID));

        assertTrue(ex.getMessage().contains("Front counter"), ex.getMessage());
        assertTrue(ex.getMessage().contains("#12"), ex.getMessage());
        verify(periodCloseRepository, never()).save(any(PeriodClose.class));
    }

    @Test
    void theOpenDrawerCheckCanBeSwitchedOff() {
        setting("accounting.period.requireNoOpenDrawers", "false");
        when(drawerSessionRepository.findForReport(any(), any()))
                .thenReturn(List.of(openSession(12L, "Front counter")));

        PeriodClose closed = periodService.close(lastMonth, USER_ID);

        assertEquals(lastMonth.toString(), closed.getPeriod());
        verify(auditLogService).logAction(eq(USER_ID), eq("PERIOD_CLOSE"), any(),
                eq("PeriodClose"), eq(null), eq(null), any());
    }

    @Test
    void graceDaysHoldBackAMonthThatOnlyJustEnded() {
        setting("accounting.period.minDaysAfterMonthEnd", "40");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> periodService.close(lastMonth, USER_ID));

        assertTrue(ex.getMessage().contains("40 day(s) of grace"), ex.getMessage());
        verify(periodCloseRepository, never()).save(any(PeriodClose.class));
    }

    @Test
    void aCompletedGraceWindowClosesTheMonth() {
        setting("accounting.period.minDaysAfterMonthEnd", "0");

        PeriodClose closed = periodService.close(lastMonth, USER_ID);

        assertEquals(lastMonth.toString(), closed.getPeriod());
        assertEquals(0, new BigDecimal("100.00").compareTo(closed.getFrozenRevenue()));
    }

    @Test
    void anUnreadableGraceSettingFallsBackToNoGrace() {
        setting("accounting.period.minDaysAfterMonthEnd", "next tuesday");

        PeriodClose closed = periodService.close(lastMonth, USER_ID);

        assertEquals(lastMonth.toString(), closed.getPeriod());
    }

    @Test
    void anUnclosableMonthIsStillRejectedBeforeTheDrawerIsInspected() {
        YearMonth thisMonth = YearMonth.from(LocalDate.now());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> periodService.close(thisMonth, USER_ID));

        assertEquals("Only a completed month can be closed", ex.getMessage());
        verify(drawerSessionRepository, never()).findOpenSessionsOpenedIn(any(), any());
    }
}
