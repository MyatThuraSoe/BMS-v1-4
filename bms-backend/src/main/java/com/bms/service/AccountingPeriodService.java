package com.bms.service;

import com.bms.entity.CashDrawerSession;
import com.bms.entity.PeriodClose;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.SystemSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class AccountingPeriodService {

    @Autowired
    private ShopTimeService shopTimeService;

    private final PeriodGuard periodGuard;
    private final PeriodCloseRepository periodCloseRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final LedgerHistoryService historyService;
    private final LedgerReadService readService;
    private final AuditLogService auditLogService;

    @Autowired
    private InventoryHistoryService inventoryHistoryService;

    @Autowired
    private CashDrawerSessionRepository drawerSessionRepository;

    public AccountingPeriodService(PeriodGuard periodGuard, PeriodCloseRepository periodCloseRepository,
                                   SystemSettingRepository systemSettingRepository, LedgerHistoryService historyService,
                                   LedgerReadService readService, AuditLogService auditLogService) {
        this.periodGuard = periodGuard;
        this.periodCloseRepository = periodCloseRepository;
        this.systemSettingRepository = systemSettingRepository;
        this.historyService = historyService;
        this.readService = readService;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<PeriodClose> list() {
        return periodCloseRepository.findAll(org.springframework.data.domain.Sort.by("period"));
    }

    public Map<String, Object> activateLedgerReads(Long userId) {
        periodGuard.lock();
        Map<String, Object> reconciliation = historyService.reconcile(null, null);
        Map<String, Object> inventoryReconciliation = inventoryHistoryService.reconcile();
        if (!Boolean.TRUE.equals(reconciliation.get("reconciled"))
                || !Boolean.TRUE.equals(inventoryReconciliation.get("reconciled"))) {
            throw new BusinessException("Ledger cutover requires complete profit and inventory history with no unexplained differences");
        }
        var setting = systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY)
                .orElseThrow(() -> new BusinessException("Ledger cutover setting is not initialized"));
        if (!readService.isActive()) {
            setting.setSettingValue(shopTimeService.now().toString());
            systemSettingRepository.save(setting);
            auditLogService.logAction(userId, "LEDGER_READ_CUTOVER", "Reports switched to reconciled accounting ledgers",
                    "SystemSetting", setting.getId(), null, setting.getSettingValue());
        }
        return Map.of("cutoverAt", setting.getSettingValue(), "reconciliation", reconciliation,
                "inventoryReconciliation", inventoryReconciliation);
    }

    public PeriodClose close(YearMonth month, Long userId) {
        periodGuard.lock();
        if (!readService.isActive()) {
            throw new BusinessException("Backfill and reconcile history, then activate ledger reads before closing periods");
        }
        if (periodCloseRepository.existsById(month.toString())) {
            throw new BusinessException("Accounting period " + month + " is already closed");
        }
        YearMonth currentMonth = YearMonth.from(shopTimeService.today());
        if (!month.isBefore(currentMonth)) {
            throw new BusinessException("Only a completed month can be closed");
        }
        // T1: the shop may want late supplier invoices to still land in the month.
        int graceDays = integerSetting("accounting.period.minDaysAfterMonthEnd", 0);
        if (graceDays > 0) {
            LocalDate firstDayAfterGrace = month.atEndOfMonth().plusDays(graceDays + 1L);
            if (shopTimeService.today().isBefore(firstDayAfterGrace)) {
                throw new BusinessException("Accounting period " + month + " needs " + graceDays
                        + " day(s) of grace after month end; it can close from " + firstDayAfterGrace);
            }
        }
        // T1: a drawer opened in the month is still taking sales and movements.
        if (booleanSetting("accounting.period.requireNoOpenDrawers", true)) {
            List<CashDrawerSession> open = drawerSessionRepository.findOpenSessionsOpenedIn(
                    month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay());
            if (!open.isEmpty()) {
                throw new BusinessException("Close the drawer sessions opened in " + month + " first: "
                        + open.stream()
                                .map(session -> "#" + session.getId() + " ("
                                        + session.getDrawer().getName() + ", opened " + session.getOpenedAt() + ")")
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
        }

        boolean sequentialRequired = booleanSetting("accounting.period.requireSequentialClose", true);
        if (sequentialRequired) {
            // The first close can be any completed month; once one month is
            // closed the following months have to go in order.
            YearMonth lastClosed = periodCloseRepository.findAll(org.springframework.data.domain.Sort.by("period")).stream()
                    .map(pc -> YearMonth.parse(pc.getPeriod()))
                    .max(java.util.Comparator.naturalOrder())
                    .orElse(null);
            if (lastClosed != null && month.isAfter(lastClosed)) {
                YearMonth firstOpen = lastClosed.plusMonths(1);
                if (!firstOpen.equals(month)) {
                    throw new BusinessException("Only sequential close is allowed; the next open month is " + firstOpen);
                }
            }
        }

        Map<String, Object> reconciliation = historyService.reconcile(month, month);
        if (!Boolean.TRUE.equals(reconciliation.get("reconciled"))
                || !Boolean.TRUE.equals(inventoryHistoryService.reconcile().get("reconciled"))) {
            throw new BusinessException("Cannot close a period with missing inventory history or unexplained ledger differences");
        }
        BigDecimal[] totals = readService.totals(month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay());
        PeriodClose close = new PeriodClose();
        close.setPeriod(month.toString());
        close.setClosedAt(shopTimeService.now());
        close.setClosedBy(userId);
        close.setFrozenRevenue(totals[0]);
        close.setFrozenCogs(totals[1]);
        close.setFrozenExpense(totals[2]);
        periodCloseRepository.save(close);
        auditLogService.logAction(userId, "PERIOD_CLOSE", "Accounting period closed: " + month,
                "PeriodClose", null, null, totals[0] + "/" + totals[1] + "/" + totals[2]);
        return close;
    }

    public void reopen(YearMonth month, String reason, Long userId) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("A reason is required to reopen an accounting period");
        }
        periodGuard.lock();
        PeriodClose close = periodCloseRepository.findById(month.toString())
                .orElseThrow(() -> new ResourceNotFoundException("Closed accounting period not found: " + month));

        List<PeriodClose> closed = periodCloseRepository.findAll(org.springframework.data.domain.Sort.by("period"));
        YearMonth latestClosed = closed.stream()
                .map(pc -> YearMonth.parse(pc.getPeriod()))
                .max(java.util.Comparator.naturalOrder())
                .orElse(null);
        if (latestClosed != null && !latestClosed.equals(month)) {
            throw new BusinessException("Accounting period " + month + " cannot be reopened while later closed months remain: " + latestClosed);
        }
        periodCloseRepository.delete(close);
        auditLogService.logAction(userId, "PERIOD_REOPEN", "Accounting period reopened: " + month + ". Reason: " + reason.trim(),
                "PeriodClose", null, close.getFrozenRevenue() + "/" + close.getFrozenCogs() + "/" + close.getFrozenExpense(),
                reason.trim());
    }

    /** A missing or unusable value falls back to the shipped default. */
    private boolean booleanSetting(String key, boolean fallback) {
        String raw = settingValue(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    /** A missing or unusable value falls back to the shipped default. */
    private int integerSetting(String key, int fallback) {
        String raw = settingValue(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String settingValue(String key) {
        return systemSettingRepository.findBySettingKey(key)
                .map(com.bms.entity.SystemSetting::getSettingValue).orElse(null);
    }
}
