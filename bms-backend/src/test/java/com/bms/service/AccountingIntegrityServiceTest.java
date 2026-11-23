package com.bms.service;

import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** BUG-8 / GL-P&L divergence: gaps are counted and the legacy path is flagged. */
@ExtendWith(MockitoExtension.class)
class AccountingIntegrityServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private LedgerReadService ledgerReadService;

    @Test
    void auditCountsMissingCostsAndReportsWhetherTheLedgerIsActive() {
        when(productRepository.countByCostPriceIsNullAndDeletedAtIsNull()).thenReturn(3L);
        when(saleItemRepository.countByCostPriceAtSaleIsNull()).thenReturn(7L);
        when(ledgerReadService.isActive()).thenReturn(false);

        AccountingIntegrityService.Report report =
                new AccountingIntegrityService(productRepository, saleItemRepository, ledgerReadService).audit();

        assertEquals(3L, report.productsMissingCost());
        assertEquals(7L, report.saleItemsMissingCost());
        assertFalse(report.ledgerReadsActive());
    }

    @Test
    void auditIsCleanOnceCostsAreSetAndTheLedgerIsActive() {
        when(productRepository.countByCostPriceIsNullAndDeletedAtIsNull()).thenReturn(0L);
        when(saleItemRepository.countByCostPriceAtSaleIsNull()).thenReturn(0L);
        when(ledgerReadService.isActive()).thenReturn(true);

        AccountingIntegrityService.Report report =
                new AccountingIntegrityService(productRepository, saleItemRepository, ledgerReadService).audit();

        assertTrue(report.productsMissingCost() == 0 && report.saleItemsMissingCost() == 0);
        assertTrue(report.ledgerReadsActive());
    }
}
