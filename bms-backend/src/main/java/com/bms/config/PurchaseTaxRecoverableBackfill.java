package com.bms.config;

import com.bms.repository.PurchaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-off data migration for C5/C6 (BMS-v1-4 business logic fix, decision D2).
 *
 * Purchases created before taxRecoverable existed never capitalised tax into
 * landed cost, which is recoverable-tax behaviour. Backfill those null rows to
 * true once so legacy RTV credits keep their pro-rata tax. New purchases set the
 * flag explicitly (default false = non-recoverable, capitalised). The UPDATE's
 * WHERE clause makes re-runs no-ops.
 */
@Component
public class PurchaseTaxRecoverableBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PurchaseTaxRecoverableBackfill.class);

    private final PurchaseRepository purchaseRepository;

    public PurchaseTaxRecoverableBackfill(PurchaseRepository purchaseRepository) {
        this.purchaseRepository = purchaseRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            int updated = purchaseRepository.backfillLegacyTaxRecoverable();
            if (updated > 0) {
                log.info("PurchaseTaxRecoverableBackfill: set taxRecoverable=true on {} legacy purchase(s)",
                        updated);
            }
        } catch (Exception e) {
            log.warn("PurchaseTaxRecoverableBackfill skipped (non-fatal): {}", e.getMessage());
        }
    }
}