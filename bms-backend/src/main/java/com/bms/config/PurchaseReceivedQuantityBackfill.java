package com.bms.config;

import com.bms.repository.PurchaseItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-off data migration for C3 (BMS-v1-4 business logic fix).
 *
 * Purchases created before the receiving feature were received in full at
 * creation time, but their items carry receivedQuantity = 0. The reversal
 * logic now reverses exactly receivedQuantity, so those legacy rows must be
 * backfilled to quantity once. The UPDATE's WHERE clause makes re-runs no-ops.
 */
@Component
public class PurchaseReceivedQuantityBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PurchaseReceivedQuantityBackfill.class);

    private final PurchaseItemRepository purchaseItemRepository;

    public PurchaseReceivedQuantityBackfill(PurchaseItemRepository purchaseItemRepository) {
        this.purchaseItemRepository = purchaseItemRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            int updated = purchaseItemRepository.backfillLegacyReceivedQuantity();
            if (updated > 0) {
                log.info("PurchaseReceivedQuantityBackfill: set receivedQuantity=quantity on {} legacy purchase item(s)",
                        updated);
            }
        } catch (Exception e) {
            log.warn("PurchaseReceivedQuantityBackfill skipped (non-fatal): {}", e.getMessage());
        }
    }
}