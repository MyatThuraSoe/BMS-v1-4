package com.bms.service;

import com.bms.entity.StockMovement;
import com.bms.repository.StockMovementRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3: single source of truth for operating inventory-loss valuations — write-offs,
 * damage, theft, internal consumption and physical count shortages (D6 shrinkage).
 * Both the P&L (ReportService.getAccountingSummary) and the GL journal (GlService)
 * read from here so inventory-loss expense agrees between them (I4).
 */
@Service
@Transactional(readOnly = true)
public class InventoryLossService {

    @Autowired
    private ShopTimeService shopTimeService;

    /** Shared count/manual adjustment reasons, kept in sync across both workflows. */
    public static final List<String> ADJUSTMENT_REASONS = List.of(
            "SPOILAGE_EXPIRY", "THEFT_SHRINKAGE", "DAMAGED_IN_STORE", "INTERNAL_CONSUMPTION",
            "COUNT_CORRECTION", "DAMAGED", "THEFT", "EXPIRED", "MISCOUNT",
            "SUPPLIER_ERROR", "RECALL", "OTHER", "RETURN_WRITE_OFF");

    public static final Set<String> POSITIVE_ADJUSTMENT_REASONS =
            Set.of("COUNT_CORRECTION", "SUPPLIER_ERROR");

    /** Count shortages are reported as shrinkage, separate from spoilage/expiry (D6). */
    public static final String SHRINKAGE = "SHRINKAGE";

    private final StockMovementRepository stockMovementRepository;

    public InventoryLossService(StockMovementRepository stockMovementRepository) {
        this.stockMovementRepository = stockMovementRepository;
    }

    /** True when the movement is an operating inventory loss (a negative valuation). */
    public boolean isOperatingLoss(StockMovement movement) {
        BigDecimal valuation = movement.getValuationAmount();
        if (valuation == null || valuation.signum() >= 0) {
            return false;
        }
        if (movement.getMovementType() == StockMovement.MovementType.REVALUATION) {
            return false;
        }
        StockMovement.ReferenceType referenceType = movement.getReferenceType();
        if (referenceType == StockMovement.ReferenceType.STOCK_COUNT) {
            return true; // physical count shortage -> shrinkage (D6)
        }
        if (referenceType == StockMovement.ReferenceType.STOCK_ADJUSTMENT) {
            return movement.getMovementType() == StockMovement.MovementType.ADJUSTMENT_OUT;
        }
        return false; // RTV, PURCHASE, SALE, TRANSFER ... are not operating losses
    }

    /** Breakdown category for a loss movement. */
    public String lossCategory(StockMovement movement) {
        if (movement.getReferenceType() == StockMovement.ReferenceType.STOCK_COUNT) {
            return SHRINKAGE;
        }
        String reason = movement.getReasonCode() != null
                ? movement.getReasonCode().trim().toUpperCase() : "OTHER";
        return "COUNT_CORRECTION".equals(reason) ? SHRINKAGE : reason;
    }

    public List<StockMovement> findOperatingLosses(LocalDateTime start, LocalDateTime end) {
        return stockMovementRepository.findForJournal(start, end).stream()
                .filter(this::isOperatingLoss)
                .toList();
    }

    /** Positive loss amounts grouped by reason category, insertion-ordered. */
    public Map<String, BigDecimal> lossesByReason(LocalDateTime start, LocalDateTime end) {
        Map<String, BigDecimal> byReason = new LinkedHashMap<>();
        for (StockMovement movement : findOperatingLosses(start, end)) {
            byReason.merge(lossCategory(movement), movement.getValuationAmount().abs(), BigDecimal::add);
        }
        return byReason;
    }

    public BigDecimal totalLosses(LocalDateTime start, LocalDateTime end) {
        return lossesByReason(start, end).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}