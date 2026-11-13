package com.bms.config;

import com.bms.repository.ProductPriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-off data migration for P8 (BMS-v1-4 business logic fix).
 *
 * Price/cost history rows written before the audit fields existed have no source.
 * Mark those LEGACY, and only promote a null priceType to SELLING where the row is
 * demonstrably a selling-price edit. New rows always set priceType and source, so
 * the WHERE clauses make re-runs no-ops. Flyway is disabled in this project; schema
 * comes from ddl-auto and data repairs run here, matching the other backfill runners.
 */
@Component
public class ProductPriceHistoryLegacyBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductPriceHistoryLegacyBackfill.class);

    private final ProductPriceHistoryRepository priceHistoryRepository;

    public ProductPriceHistoryLegacyBackfill(ProductPriceHistoryRepository priceHistoryRepository) {
        this.priceHistoryRepository = priceHistoryRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            int selling = priceHistoryRepository.backfillLegacySellingPriceType();
            int legacy = priceHistoryRepository.backfillLegacySource();
            if (selling > 0 || legacy > 0) {
                log.info("ProductPriceHistoryLegacyBackfill: priceType=SELLING on {} row(s), source=LEGACY on {} row(s)",
                        selling, legacy);
            }
        } catch (Exception e) {
            log.warn("ProductPriceHistoryLegacyBackfill skipped (non-fatal): {}", e.getMessage());
        }
    }
}