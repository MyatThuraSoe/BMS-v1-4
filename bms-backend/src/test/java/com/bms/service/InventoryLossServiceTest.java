package com.bms.service;

import com.bms.entity.StockMovement;
import com.bms.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * P3: the shared classifier that decides which stock movements are operating
 * inventory losses. Both the P&L and the GL journal read it, so the two must
 * agree (I4). Count shortages are shrinkage (D6); RTV, revaluations, neutral
 * count corrections and positive gains are NOT operating losses.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryLossServiceTest {

    @Mock private StockMovementRepository stockMovementRepository;

    private InventoryLossService service;

    @BeforeEach
    void setUp() {
        service = new InventoryLossService(stockMovementRepository);
    }

    private StockMovement movement(StockMovement.MovementType type,
                                   StockMovement.ReferenceType reference,
                                   String reason, String valuation) {
        StockMovement m = new StockMovement();
        m.setId(1L);
        m.setMovementType(type);
        m.setReferenceType(reference);
        m.setReasonCode(reason);
        m.setValuationAmount(valuation == null ? null : new BigDecimal(valuation));
        m.setMovementDate(LocalDateTime.now());
        return m;
    }

    @Test
    void p3_spoilageWriteOffIsOperatingLoss() {
        StockMovement m = movement(StockMovement.MovementType.ADJUSTMENT_OUT,
                StockMovement.ReferenceType.STOCK_ADJUSTMENT, "SPOILAGE_EXPIRY", "-120.00");
        assertTrue(service.isOperatingLoss(m));
        assertEquals("SPOILAGE_EXPIRY", service.lossCategory(m));
    }

    @Test
    void p3_theftDamageAndInternalConsumptionAreLosses() {
        for (String reason : List.of("THEFT_SHRINKAGE", "DAMAGED_IN_STORE", "INTERNAL_CONSUMPTION")) {
            StockMovement m = movement(StockMovement.MovementType.ADJUSTMENT_OUT,
                    StockMovement.ReferenceType.STOCK_ADJUSTMENT, reason, "-10.00");
            assertTrue(service.isOperatingLoss(m), reason + " must be an operating loss");
        }
    }

    @Test
    void p3_countShortageIsShrinkage() {
        StockMovement m = movement(StockMovement.MovementType.ADJUSTMENT_OUT,
                StockMovement.ReferenceType.STOCK_COUNT, "MISCOUNT", "-45.00");
        assertTrue(service.isOperatingLoss(m));
        assertEquals(InventoryLossService.SHRINKAGE, service.lossCategory(m));
    }

    @Test
    void p3_rtvIsNotOperatingLoss() {
        StockMovement m = movement(StockMovement.MovementType.OUT,
                StockMovement.ReferenceType.RTV, null, "-50.00");
        assertFalse(service.isOperatingLoss(m));
    }

    @Test
    void p3_revaluationIsNotOperatingLoss() {
        StockMovement m = movement(StockMovement.MovementType.REVALUATION,
                StockMovement.ReferenceType.STOCK_ADJUSTMENT, "SPOILAGE_EXPIRY", "-20.00");
        assertFalse(service.isOperatingLoss(m));
    }

    @Test
    void p3_neutralCountCorrectionIsNotLoss() {
        StockMovement m = movement(StockMovement.MovementType.ADJUSTMENT_OUT,
                StockMovement.ReferenceType.STOCK_ADJUSTMENT, "COUNT_CORRECTION", "-5.00");
        assertFalse(service.isOperatingLoss(m));
    }

    @Test
    void p3_positiveGainIsNotLoss() {
        StockMovement m = movement(StockMovement.MovementType.ADJUSTMENT_IN,
                StockMovement.ReferenceType.STOCK_ADJUSTMENT, "COUNT_CORRECTION", "10.00");
        assertFalse(service.isOperatingLoss(m));
    }

    @Test
    void p3_nullValuationIsNotLoss() {
        StockMovement m = movement(StockMovement.MovementType.ADJUSTMENT_OUT,
                StockMovement.ReferenceType.STOCK_ADJUSTMENT, "SPOILAGE_EXPIRY", null);
        assertFalse(service.isOperatingLoss(m));
    }

    @Test
    void p3_lossesByReasonAggregatesAndExcludesNonLosses() {
        List<StockMovement> movements = List.of(
                movement(StockMovement.MovementType.ADJUSTMENT_OUT,
                        StockMovement.ReferenceType.STOCK_ADJUSTMENT, "SPOILAGE_EXPIRY", "-120.00"),
                movement(StockMovement.MovementType.ADJUSTMENT_OUT,
                        StockMovement.ReferenceType.STOCK_ADJUSTMENT, "THEFT_SHRINKAGE", "-30.00"),
                movement(StockMovement.MovementType.ADJUSTMENT_OUT,
                        StockMovement.ReferenceType.STOCK_COUNT, "MISCOUNT", "-45.00"),
                movement(StockMovement.MovementType.OUT,
                        StockMovement.ReferenceType.RTV, null, "-50.00"),
                movement(StockMovement.MovementType.REVALUATION,
                        StockMovement.ReferenceType.STOCK_ADJUSTMENT, "SPOILAGE_EXPIRY", "-20.00"),
                movement(StockMovement.MovementType.ADJUSTMENT_IN,
                        StockMovement.ReferenceType.STOCK_ADJUSTMENT, "COUNT_CORRECTION", "10.00"),
                movement(StockMovement.MovementType.ADJUSTMENT_OUT,
                        StockMovement.ReferenceType.STOCK_ADJUSTMENT, "COUNT_CORRECTION", "-5.00"));
        lenient().when(stockMovementRepository.findForJournal(any(), any())).thenReturn(movements);

        Map<String, BigDecimal> byReason = service.lossesByReason(
                LocalDateTime.now().minusDays(30), LocalDateTime.now());

        assertEquals(3, byReason.size());
        assertEquals(0, new BigDecimal("120.00").compareTo(byReason.get("SPOILAGE_EXPIRY")));
        assertEquals(0, new BigDecimal("30.00").compareTo(byReason.get("THEFT_SHRINKAGE")));
        assertEquals(0, new BigDecimal("45.00").compareTo(byReason.get(InventoryLossService.SHRINKAGE)));
        assertEquals(0, new BigDecimal("195.00").compareTo(service.totalLosses(
                LocalDateTime.now().minusDays(30), LocalDateTime.now())));
    }
}
