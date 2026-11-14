package com.bms.repository;

import com.bms.entity.ProductPriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductPriceHistoryRepository extends JpaRepository<ProductPriceHistory, Long> {

    // P8 req 9 / O7: id is a stable tiebreaker when changedAt collides.
    List<ProductPriceHistory> findByProductIdOrderByChangedAtDescIdDesc(Long productId);

    List<ProductPriceHistory> findByProductIdAndPriceTypeOrderByChangedAtDescIdDesc(
            Long productId, ProductPriceHistory.PriceType priceType);

    /**
     * P8 req 2: only rows demonstrably written as selling-price edits may be backfilled
     * to SELLING. Legacy rows with no source stay null and are marked LEGACY below.
     * Idempotent.
     */
    @Modifying
    @Query("UPDATE ProductPriceHistory h SET h.priceType = com.bms.entity.ProductPriceHistory.PriceType.SELLING "
            + "WHERE h.priceType IS NULL "
            + "AND h.source = com.bms.entity.ProductPriceHistory.Source.SELLING_PRICE_EDIT")
    int backfillLegacySellingPriceType();

    /** P8 req 1: rows with no recorded source are legacy. Idempotent. */
    @Modifying
    @Query("UPDATE ProductPriceHistory h SET h.source = com.bms.entity.ProductPriceHistory.Source.LEGACY "
            + "WHERE h.source IS NULL")
    int backfillLegacySource();
}