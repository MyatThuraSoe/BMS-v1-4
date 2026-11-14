package com.bms.service;

import com.bms.entity.PeriodClose;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.SystemSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class AccountingPeriodService {

    private final PeriodGuard periodGuard;
    private final PeriodCloseRepository periodCloseRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final LedgerHistoryService historyService;
    private final LedgerReadService readService;
    private final AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired
    private InventoryHistoryService inventoryHistoryService;

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
            setting.setSettingValue(LocalDateTime.now().toString());
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
        Map<String, Object> reconciliation = historyService.reconcile(month, month);
        if (!Boolean.TRUE.equals(reconciliation.get("reconciled"))
                || !Boolean.TRUE.equals(inventoryHistoryService.reconcile().get("reconciled"))) {
            throw new BusinessException("Cannot close a period with missing inventory history or unexplained ledger differences");
        }
        BigDecimal[] totals = readService.totals(month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay());
        PeriodClose close = new PeriodClose();
        close.setPeriod(month.toString());
        close.setClosedAt(LocalDateTime.now());
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
        periodCloseRepository.delete(close);
        auditLogService.logAction(userId, "PERIOD_REOPEN", "Accounting period reopened: " + month + ". Reason: " + reason.trim(),
                "PeriodClose", null, close.getFrozenRevenue() + "/" + close.getFrozenCogs() + "/" + close.getFrozenExpense(),
                reason.trim());
    }
}
