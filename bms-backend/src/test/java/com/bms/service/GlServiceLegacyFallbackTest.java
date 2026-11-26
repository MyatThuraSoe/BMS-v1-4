package com.bms.service;

import com.bms.entity.GlAccount;
import com.bms.entity.GlMapping;
import com.bms.repository.ArPaymentRepository;
import com.bms.repository.CashDrawerMovementRepository;
import com.bms.repository.ExpenseRepository;
import com.bms.repository.GlAccountRepository;
import com.bms.repository.GlMappingRepository;
import com.bms.repository.PurchasePaymentRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/** A shop that has not cut over yet still needs its GL journal to export. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlServiceLegacyFallbackTest {

    @Mock private GlAccountRepository accountRepository;
    @Mock private GlMappingRepository mappingRepository;
    @Mock private PurchaseRepository purchaseRepository;
    @Mock private SaleRepository saleRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private ArPaymentRepository arPaymentRepository;
    @Mock private SaleReturnRepository saleReturnRepository;
    @Mock private CashDrawerMovementRepository cashDrawerMovementRepository;
    @Mock private LedgerReadService ledgerReadService;
    @Mock private PurchasePaymentRepository purchasePaymentRepository;

    private GlService glService;

    @BeforeEach
    void setUp() {
        glService = new GlService(accountRepository, mappingRepository,
                purchaseRepository, saleRepository, stockMovementRepository,
                new InventoryLossService(stockMovementRepository),
                expenseRepository, arPaymentRepository, saleReturnRepository,
                cashDrawerMovementRepository);
        ReflectionTestUtils.setField(glService, "ledgerReadService", ledgerReadService);
        ReflectionTestUtils.setField(glService, "purchasePaymentRepository", purchasePaymentRepository);

        lenient().when(ledgerReadService.isActive()).thenReturn(false);

        List<GlMapping> mappings = new ArrayList<>();
        for (GlMapping.EventType type : GlMapping.EventType.values()) {
            GlAccount account = new GlAccount();
            account.setCode(type.name());
            account.setName(type.name());
            GlMapping mapping = new GlMapping();
            mapping.setEventType(type);
            mapping.setAccount(account);
            mappings.add(mapping);
        }
        lenient().when(mappingRepository.findAll()).thenReturn(mappings);
        lenient().when(purchaseRepository.findForJournal(any(), any())).thenReturn(List.of());
        lenient().when(saleRepository.findForJournal(any(), any())).thenReturn(List.of());
        lenient().when(saleRepository.findVoidedForJournal(any(), any())).thenReturn(List.of());
        lenient().when(saleReturnRepository.findForJournal(any(), any())).thenReturn(List.of());
        lenient().when(saleReturnRepository.findBySaleIdOrderByReturnDateDesc(any())).thenReturn(List.of());
        lenient().when(expenseRepository.findFiltered(any(), any(), any())).thenReturn(List.of());
        lenient().when(stockMovementRepository.findForJournal(any(), any())).thenReturn(List.of());
        lenient().when(stockMovementRepository.findByReference(any(), any())).thenReturn(List.of());
        lenient().when(arPaymentRepository.findForJournal(any(), any())).thenReturn(List.of());
        lenient().when(purchasePaymentRepository.findForJournal(any(), any())).thenReturn(List.of());
        lenient().when(cashDrawerMovementRepository.findForJournal(any(), any())).thenReturn(List.of());
    }

    @Test
    void journalUsesLegacySourcesWhenLedgerReadsAreInactive() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 26);

        assertDoesNotThrow(() -> glService.journal(from, to));

        verify(purchaseRepository).findForJournal(any(), any());
        verify(saleRepository).findForJournal(any(), any());
    }
}
