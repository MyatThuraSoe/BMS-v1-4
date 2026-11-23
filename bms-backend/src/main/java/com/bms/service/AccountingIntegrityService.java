package com.bms.service;

import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

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
 */
@Service
public class AccountingIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(AccountingIntegrityService.class);

    private final ProductRepository productRepository;
    private final SaleItemRepository saleItemRepository;
    private final LedgerReadService ledgerReadService;

    public AccountingIntegrityService(ProductRepository productRepository,
                                      SaleItemRepository saleItemRepository,
                                      LedgerReadService ledgerReadService) {
        this.productRepository = productRepository;
        this.saleItemRepository = saleItemRepository;
        this.ledgerReadService = ledgerReadService;
    }

    public Report audit() {
        return new Report(
                productRepository.countByCostPriceIsNullAndDeletedAtIsNull(),
                saleItemRepository.countByCostPriceAtSaleIsNull(),
                ledgerReadService.isActive());
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
            if (!report.ledgerReadsActive()) {
                log.warn("Accounting integrity: ledger reads are not active, so the GL and P&L use the legacy "
                        + "migration path. Backfill history and activate ledger reads before closing periods.");
            }
        } catch (RuntimeException ex) {
            // A diagnostic must never stop the shop from opening.
            log.debug("Accounting integrity check skipped: {}", ex.getMessage());
        }
    }

    public record Report(long productsMissingCost, long saleItemsMissingCost, boolean ledgerReadsActive) {
    }
}
