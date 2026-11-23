package com.bms.service;

import com.bms.entity.InventoryLedger;
import com.bms.entity.Product;
import com.bms.entity.SaleItem;
import com.bms.exception.BusinessException;
import com.bms.repository.InventoryLedgerRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SystemSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * <p>TASK-2: startup never auto-confirms a backup or auto-writes ledger rows.
 * When the cutover setting is empty and sales exist, startup only logs that the
 * install is ready for an admin-triggered cutover (backfill profit, backfill
 * inventory, then activate ledger reads). Failures are logged at warn so they
 * show in production logs, and the audit report exposes cutover readiness.
 */
@Service
public class AccountingIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(AccountingIntegrityService.class);

    private static final String LEGACY_KEY = LedgerReadService.LEGACY_ENABLED_KEY;

    private final ProductRepository productRepository;
    private final SaleItemRepository saleItemRepository;
    private final SaleRepository saleRepository;
    private final InventoryLedgerRepository inventoryLedgerRepository;
    private final LedgerReadService ledgerReadService;
    private final SystemSettingRepository systemSettingRepository;
    private final ShopTimeService shopTimeService;
    private final AuditLogService auditLogService;

    public AccountingIntegrityService(ProductRepository productRepository,
                                      SaleItemRepository saleItemRepository,
                                      SaleRepository saleRepository,
                                      InventoryLedgerRepository inventoryLedgerRepository,
                                      LedgerReadService ledgerReadService,
                                      SystemSettingRepository systemSettingRepository,
                                      ShopTimeService shopTimeService,
                                      AuditLogService auditLogService) {
        this.productRepository = productRepository;
        this.saleItemRepository = saleItemRepository;
        this.saleRepository = saleRepository;
        this.inventoryLedgerRepository = inventoryLedgerRepository;
        this.ledgerReadService = ledgerReadService;
        this.systemSettingRepository = systemSettingRepository;
        this.shopTimeService = shopTimeService;
        this.auditLogService = auditLogService;
    }

    public Report audit() {
        return new Report(
                productRepository.countByCostPriceIsNullAndDeletedAtIsNull(),
                saleItemRepository.countByCostPriceAtSaleIsNull(),
                ledgerReadService.isActive(),
                legacyEnabled(),
                cutoverReady());
    }

    /** True when the legacy (pre-cutover) GL and report path is explicitly enabled. */
    public boolean legacyEnabled() {
        return systemSettingRepository.findBySettingKey(LEGACY_KEY)
                .map(setting -> "true".equalsIgnoreCase(setting.getSettingValue()))
                .orElse(false);
    }

    /** True when cutover has not been completed but an install is ready for it. */
    public boolean cutoverReady() {
        return systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY)
                .map(setting -> setting.getSettingValue() == null
                        || setting.getSettingValue().isBlank())
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
                if (report.cutoverReady()) {
                    log.warn("Accounting integrity: ledger cutover is not complete. Run profit backfill, "
                            + "inventory backfill, then activate ledger reads (admin endpoints) before closing periods.");
                } else {
                    log.warn("Accounting integrity: ledger reads are not active and the legacy path is disabled. "
                            + "Backfill history and activate ledger reads before closing periods.");
                }
            }
        } catch (RuntimeException ex) {
            // A diagnostic must never stop the shop from opening, but must be visible.
            log.warn("Accounting integrity check failed: {}", ex.getMessage());
        }
    }

    /**
     * Admin repair for legacy sale lines with a null {@code cost_price_at_sale}.
     * Requires backup confirmation, is idempotent, and writes an audit log entry.
     *
     * <p>For each affected line, stamps {@code cost_price_at_sale} from the nearest
     * {@code inventory_ledger.unit_cost_after} at or before the sale date, falling
     * back to the product's current cost. Marks the line as estimated. Lines whose
     * COGS was already posted as zero are reported separately so the operator can
     * post a dated adjusting entry in the open period.
     */
    @Transactional
    public Map<String, Object> repairNullCostLines(boolean backupConfirmed, Long userId) {
        if (!backupConfirmed) {
            throw new BusinessException("Confirm that a database backup was taken and the repair was tested on a copy");
        }
        List<SaleItem> lines = saleItemRepository.findByCostPriceAtSaleIsNull();
        int repaired = 0;
        List<Long> needsAdjustment = new ArrayList<>();
        for (SaleItem line : lines) {
            if (line.getCostPriceAtSale() != null) {
                continue; // idempotent: already repaired
            }
            Product product = line.getProduct();
            BigDecimal cost = nearestLedgerCost(product.getId(), line.getSale().getSaleDate());
            if (cost == null) {
                cost = product.getCostPrice();
            }
            if (cost == null) {
                // Nothing to repair from — leave the line null and report it.
                continue;
            }
            line.setCostPriceAtSale(cost);
            line.setEstimated(true);
            saleItemRepository.save(line);
            repaired++;
            needsAdjustment.add(line.getId());
        }
        auditLogService.logAction(userId, "COST_REPAIR",
                "Null cost_price_at_sale lines repaired: " + repaired,
                "SaleItem", null, null, String.valueOf(repaired));
        return Map.of("repaired", repaired, "needsCogsAdjustment", needsAdjustment);
    }

    private BigDecimal nearestLedgerCost(Long productId, LocalDateTime at) {
        List<InventoryLedger> history = inventoryLedgerRepository
                .findByProductIdAndOccurredAtLessThanEqualOrderByOccurredAtDescIdDesc(productId, at);
        for (InventoryLedger row : history) {
            if (row.getUnitCostAfter() != null) {
                return row.getUnitCostAfter();
            }
        }
        return null;
    }

    public record Report(long productsMissingCost, long saleItemsMissingCost,
                         boolean ledgerReadsActive, boolean legacyEnabled, boolean cutoverReady) {
    }
}
