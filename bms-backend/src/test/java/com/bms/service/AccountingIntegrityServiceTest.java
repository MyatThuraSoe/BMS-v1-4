package com.bms.service;

import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** BUG-8 / GL-P&L divergence: gaps are counted and the legacy path is flagged. */
@ExtendWith(MockitoExtension.class)
class AccountingIntegrityServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private SaleRepository saleRepository;
    @Mock private LedgerHistoryService ledgerHistoryService;
    @Mock private LedgerReadService ledgerReadService;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private ShopTimeService shopTimeService;

    @Test
    void auditCountsMissingCostsAndReportsWhetherTheLedgerIsActive() {
        when(productRepository.countByCostPriceIsNullAndDeletedAtIsNull()).thenReturn(3L);
        when(saleItemRepository.countByCostPriceAtSaleIsNull()).thenReturn(7L);
        when(ledgerReadService.isActive()).thenReturn(false);
        when(systemSettingRepository.findBySettingKey("accounting.ledger.legacy.enabled"))
                .thenReturn(Optional.empty());

        AccountingIntegrityService.Report report =
                new AccountingIntegrityService(productRepository, saleItemRepository, saleRepository,
                        ledgerHistoryService, ledgerReadService, systemSettingRepository, shopTimeService).audit();

        assertEquals(3L, report.productsMissingCost());
        assertEquals(7L, report.saleItemsMissingCost());
        assertFalse(report.ledgerReadsActive());
        assertFalse(report.legacyEnabled());
    }

    @Test
    void auditIsCleanOnceCostsAreSetAndTheLedgerIsActive() {
        when(productRepository.countByCostPriceIsNullAndDeletedAtIsNull()).thenReturn(0L);
        when(saleItemRepository.countByCostPriceAtSaleIsNull()).thenReturn(0L);
        when(ledgerReadService.isActive()).thenReturn(true);
        when(systemSettingRepository.findBySettingKey("accounting.ledger.legacy.enabled"))
                .thenReturn(Optional.empty());

        AccountingIntegrityService.Report report =
                new AccountingIntegrityService(productRepository, saleItemRepository, saleRepository,
                        ledgerHistoryService, ledgerReadService, systemSettingRepository, shopTimeService).audit();

        assertTrue(report.productsMissingCost() == 0 && report.saleItemsMissingCost() == 0);
        assertTrue(report.ledgerReadsActive());
        assertFalse(report.legacyEnabled());
    }

    @Test
    void legacyEnabledReturnsTrueWhenSettingIsTrue() {
        var setting = new com.bms.entity.SystemSetting();
        setting.setSettingKey("accounting.ledger.legacy.enabled");
        setting.setSettingValue("true");
        when(systemSettingRepository.findBySettingKey("accounting.ledger.legacy.enabled"))
                .thenReturn(Optional.of(setting));

        AccountingIntegrityService service =
                new AccountingIntegrityService(productRepository, saleItemRepository, saleRepository,
                        ledgerHistoryService, ledgerReadService, systemSettingRepository, shopTimeService);

        assertTrue(service.legacyEnabled());
    }

    @Test
    void legacyEnabledReturnsFalseWhenSettingIsMissing() {
        when(systemSettingRepository.findBySettingKey("accounting.ledger.legacy.enabled"))
                .thenReturn(Optional.empty());

        AccountingIntegrityService service =
                new AccountingIntegrityService(productRepository, saleItemRepository, saleRepository,
                        ledgerHistoryService, ledgerReadService, systemSettingRepository, shopTimeService);

        assertFalse(service.legacyEnabled());
    }
}
