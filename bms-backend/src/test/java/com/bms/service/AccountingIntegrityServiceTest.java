package com.bms.service;

import com.bms.repository.InventoryLedgerRepository;
import com.bms.repository.ProfitLedgerRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** BUG-8 / GL-P&L divergence: gaps are counted and the legacy path is flagged. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountingIntegrityServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private SaleRepository saleRepository;
    @Mock private InventoryLedgerRepository inventoryLedgerRepository;
    @Mock private ProfitLedgerRepository profitLedgerRepository;
    @Mock private LedgerReadService ledgerReadService;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private ShopTimeService shopTimeService;
    @Mock private AuditLogService auditLogService;

    @Test
    void auditCountsMissingCostsAndReportsCutoverReadiness() {
        when(productRepository.countByCostPriceIsNullAndDeletedAtIsNull()).thenReturn(3L);
        when(saleItemRepository.countByCostPriceAtSaleIsNull()).thenReturn(7L);
        when(ledgerReadService.isActive()).thenReturn(false);
        when(systemSettingRepository.findBySettingKey("accounting.ledger.legacy.enabled"))
                .thenReturn(Optional.empty());
        when(systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY))
                .thenReturn(Optional.of(new com.bms.entity.SystemSetting()));

        AccountingIntegrityService.Report report =
                new AccountingIntegrityService(productRepository, saleItemRepository, saleRepository,
                        inventoryLedgerRepository, profitLedgerRepository, ledgerReadService,
                        systemSettingRepository, shopTimeService, auditLogService).audit();

        assertEquals(3L, report.productsMissingCost());
        assertEquals(7L, report.saleItemsMissingCost());
        assertFalse(report.ledgerReadsActive());
        assertFalse(report.legacyEnabled());
        assertTrue(report.cutoverReady());
    }

    @Test
    void auditIsCleanOnceCostsAreSetAndTheLedgerIsActive() {
        when(productRepository.countByCostPriceIsNullAndDeletedAtIsNull()).thenReturn(0L);
        when(saleItemRepository.countByCostPriceAtSaleIsNull()).thenReturn(0L);
        when(ledgerReadService.isActive()).thenReturn(true);
        when(systemSettingRepository.findBySettingKey("accounting.ledger.legacy.enabled"))
                .thenReturn(Optional.empty());
        var setting = new com.bms.entity.SystemSetting();
        setting.setSettingKey(LedgerReadService.CUTOVER_KEY);
        setting.setSettingValue("2026-01-01T00:00:00");
        when(systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY))
                .thenReturn(Optional.of(setting));

        AccountingIntegrityService.Report report =
                new AccountingIntegrityService(productRepository, saleItemRepository, saleRepository,
                        inventoryLedgerRepository, profitLedgerRepository, ledgerReadService,
                        systemSettingRepository, shopTimeService, auditLogService).audit();

        assertTrue(report.productsMissingCost() == 0 && report.saleItemsMissingCost() == 0);
        assertTrue(report.ledgerReadsActive());
        assertFalse(report.legacyEnabled());
        assertFalse(report.cutoverReady());
    }

    @Test
    void legacyEnabledReturnsTrueWhenSettingIsTrue() {
        var setting = new com.bms.entity.SystemSetting();
        setting.setSettingKey("accounting.ledger.legacy.enabled");
        setting.setSettingValue("true");
        when(systemSettingRepository.findBySettingKey("accounting.ledger.legacy.enabled"))
                .thenReturn(Optional.of(setting));
        when(systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY))
                .thenReturn(Optional.of(new com.bms.entity.SystemSetting()));

        AccountingIntegrityService service =
                new AccountingIntegrityService(productRepository, saleItemRepository, saleRepository,
                        inventoryLedgerRepository, profitLedgerRepository, ledgerReadService,
                        systemSettingRepository, shopTimeService, auditLogService);

        assertTrue(service.legacyEnabled());
    }

    @Test
    void legacyEnabledReturnsFalseWhenSettingIsMissing() {
        when(systemSettingRepository.findBySettingKey("accounting.ledger.legacy.enabled"))
                .thenReturn(Optional.empty());
        when(systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY))
                .thenReturn(Optional.of(new com.bms.entity.SystemSetting()));

        AccountingIntegrityService service =
                new AccountingIntegrityService(productRepository, saleItemRepository, saleRepository,
                        inventoryLedgerRepository, profitLedgerRepository, ledgerReadService,
                        systemSettingRepository, shopTimeService, auditLogService);

        assertFalse(service.legacyEnabled());
    }
}
