package com.bms.service;

import com.bms.service.LedgerHistoryService;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SystemSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * BUG-8 / GL-P&L divergence: makes accounting data gaps visible instead of
 * letting them surface as silently wrong numbers.
 *
 * <p>A product with no cost price can no longer post a zero-COGS sale, but only
 * when someone tries to sell it. Legacy imports, restores and hand-edited rows
 * can otherwise sit unnoticed. The same is true of historical sale lines whose
 * {@code cost_price_at_sale} was never captured: ledger rebuilds report them,
 * but nothing looks until a rebuild runs. This audit counts both at startup.
 *
 * <p>It also flags an install that is still reading reports from the legacy
 * (non-ledger) path. That path is migration-only; every shop is expected to
 * backfill and activate ledger reads so the GL and the P&amp;L share one source.
 *
 * <p>TASK-2: on startup or upgrade, if sales exist and the cutover setting is
 * empty, run the existing backfill, verify it, then set accounting.ledger.cutover.
 * The legacy GL and report path is only available behind an explicit migration
 * flag (accounting.ledger.legacy.enabled), default off.
 */
@Service
public class AccountingIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(AccountingIntegrityService.class);

    private static final String LEGACY_KEY = "accounting.ledger.legacy.enabled";

    private final ProductRepository productRepository;
    private final SaleItemRepository saleItemRepository;
    private final SaleRepository saleRepository;
    private final LedgerHistoryService ledgerHistoryService;
    private final LedgerReadService ledgerReadService;
    private final SystemSettingRepository systemSettingRepository;
    private final ShopTimeService shopTimeService;

    public AccountingIntegrityService(ProductRepository productRepository,
                                      SaleItemRepository saleItemRepository,
                                      SaleRepository saleRepository,
                                      LedgerHistoryService ledgerHistoryService,
                                      LedgerReadService ledgerReadService,
                                      SystemSettingRepository systemSettingRepository,
                                      ShopTimeService shopTimeService) {
        this.productRepository = productRepository;
        this.saleItemRepository = saleItemRepository;
        this.saleRepository = saleRepository;
        this.ledgerHistoryService = ledgerHistoryService;
        this.ledgerReadService = ledgerReadService;
        this.systemSettingRepository = systemSettingRepository;
        this.shopTimeService = shopTimeService;
    }

    public Report audit() {
        return new Report(
                productRepository.countByCostPriceIsNullAndDeletedAtIsNull(),
                saleItemRepository.countByCostPriceAtSaleIsNull(),
                ledgerReadService.isActive(),
                legacyEnabled());
    }

    /** True when the legacy (pre-cutover) GL and report path is explicitly enabled. */
    public boolean legacyEnabled() {
        return systemSettingRepository.findBySettingKey(LEGACY_KEY)
                .map(setting -> "true".equalsIgnoreCase(setting.getSettingValue()))
                .orElse(false);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logOnStartup() {
        try {
            Report report = audit();
            if (report.productsMissingCost() > 0) {
                log.warn("Accounting integrity: {} product(s) have no cost price. "
                                + "Set a cost before selling them; the sale will otherwise be rejected.",
                        report.productsMissingCost());
            }
            if (report.saleItemsMissingCost() > 0) {
                log.warn("Accounting integrity: {} historical sale line(s) have no cost price at sale. "
                                + "Ledger rebuilds will treat them as blocking issues until the cost is repaired.",
                        report.saleItemsMissingCost());
            }
            if (!report.ledgerReadsActive() && !report.legacyEnabled()) {
                log.warn("Accounting integrity: ledger reads are not active and the legacy path is disabled. "
                        + "Backfill history and activate ledger reads before closing periods.");
            }
        } catch (RuntimeException ex) {
            // A diagnostic must never stop the shop from opening.
            log.debug("Accounting integrity check skipped: {}", ex.getMessage());
        }
    }

    /**
     * TASK-2: auto-cutover on startup. If the cutover setting is empty, attempt to
     * backfill history and activate ledger reads. Fresh installs with no sales are
     * cut over immediately. Any failure is logged and leaves the legacy path as the
     * fallback (only if explicitly enabled).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoCutoverOnStartup() {
        try {
            var cutoverSetting = systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY)
                    .orElseThrow(() -> new IllegalStateException("Cutover setting not initialized"));
            if (cutoverSetting.getSettingValue() != null && !cutoverSetting.getSettingValue().isBlank()) {
                return; // already cut over
            }

            long salesCount = saleRepository.count();
            if (salesCount == 0) {
                // Fresh install: no history to backfill, set cutover now.
                cutoverSetting.setSettingValue(shopTimeService.now().toString());
                systemSettingRepository.save(cutoverSetting);
                log.info("Accounting cutover set at startup (fresh install, no sales to backfill)");
                return;
            }

            // Sales exist: attempt backfill, then verify.
            var result = ledgerHistoryService.backfillProfit(true, null);
            if (Boolean.TRUE.equals(result.get("blocked"))) {
                log.warn("Auto-cutover blocked: backfill reported issues: {}", result.get("issues"));
                return;
            }
            var reconciliation = ledgerHistoryService.reconcile(null, null);
            if (!Boolean.TRUE.equals(reconciliation.get("reconciled"))) {
                log.warn("Auto-cutover blocked: reconciliation not clean: {}", reconciliation.get("issues"));
                return;
            }

            cutoverSetting.setSettingValue(shopTimeService.now().toString());
            systemSettingRepository.save(cutoverSetting);
            log.info("Auto-cutover completed at startup, {} sale(s) backfilled", salesCount);
        } catch (RuntimeException ex) {
            // Auto-cutover must never stop the shop from opening.
            log.debug("Auto-cutover skipped: {}", ex.getMessage());
        }
    }

    public record Report(long productsMissingCost, long saleItemsMissingCost,
                         boolean ledgerReadsActive, boolean legacyEnabled) {
    }
}
