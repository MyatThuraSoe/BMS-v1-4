package com.bms.service;

import com.bms.dto.response.AccountingSummaryResponse;
import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.GlAccount;
import com.bms.entity.GlMapping;
import com.bms.entity.StockMovement;
import com.bms.repository.ExpenseRepository;
import com.bms.repository.GlAccountRepository;
import com.bms.repository.GlMappingRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;

/**
 * P3 acceptance (spec 5.4): write off 12 expired units @ 10 -> the P&L expense
 * includes 120.00 under spoilage/expiry, and GL expense equals P&L expense for
 * the period. Both read the shared InventoryLossService over the same movements.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceInventoryLossTest {

    @Mock private SaleRepository saleRepository;
    @Mock private ExpenseRepository expenseRepository;
    @Mock private SaleReturnRepository saleReturnRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private GlAccountRepository accountRepository;
    @Mock private GlMappingRepository mappingRepository;
    @Mock private PurchaseRepository purchaseRepository;

    private ReportService reportService;
    private GlService glService;
    private final List<StockMovement> movements = new ArrayList<>();

    @BeforeEach
    void setUp() {
        InventoryLossService inventoryLossService = new InventoryLossService(stockMovementRepository);

        reportService = Mockito.spy(new ReportService());
        ReflectionTestUtils.setField(reportService, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(reportService, "expenseRepository", expenseRepository);
        ReflectionTestUtils.setField(reportService, "saleReturnRepository", saleReturnRepository);
        ReflectionTestUtils.setField(reportService, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(reportService, "inventoryLossService", inventoryLossService);

        glService = new GlService(accountRepository, mappingRepository, purchaseRepository,
                saleRepository, stockMovementRepository, inventoryLossService);

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
        lenient().when(saleRepository.sumOutstandingAr()).thenReturn(BigDecimal.ZERO);
        lenient().when(expenseRepository.findFiltered(any(), any(), any())).thenReturn(List.of());
        lenient().when(saleReturnRepository.findByReturnDateBetween(any(), any())).thenReturn(List.of());
        lenient().when(stockMovementRepository.findForJournal(any(), any())).thenReturn(movements);

        // Revenue/COGS come from the profit summary; isolate the expense path under test.
        Map<String, Object> profit = new LinkedHashMap<>();
        profit.put("revenue", new BigDecimal("1000.00"));
        profit.put("cogs", new BigDecimal("600.00"));
        doReturn(profit).when(reportService).getProfitSummary(any(), any());
    }

    private StockMovement loss(StockMovement.ReferenceType reference, String reason, String valuation) {
        StockMovement m = new StockMovement();
        m.setId((long) (movements.size() + 1));
        m.setMovementType(StockMovement.MovementType.ADJUSTMENT_OUT);
        m.setReferenceType(reference);
        m.setReasonCode(reason);
        m.setValuationAmount(new BigDecimal(valuation));
        m.setMovementDate(LocalDateTime.now());
        movements.add(m);
        return m;
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    private static BigDecimal glOperatingExpenseDebit(List<JournalEntryResponse> entries) {
        return entries.stream()
                .filter(e -> "OPERATING_EXPENSE".equals(e.getEventType()))
                .map(JournalEntryResponse::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumDebits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumCredits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void p3_writeOffReachesPnlAndAgreesWithGl() {
        loss(StockMovement.ReferenceType.STOCK_ADJUSTMENT, "SPOILAGE_EXPIRY", "-120.00");

        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = LocalDate.now();
        AccountingSummaryResponse summary = reportService.getAccountingSummary(start, end);

        assertMoney("1000.00", summary.getTotalIncome());
        assertMoney("600.00", summary.getTotalCogs());
        assertMoney("400.00", summary.getGrossProfit());
        assertMoney("120.00", summary.getTotalInventoryLoss());
        assertMoney("120.00", summary.getTotalExpenses());
        assertMoney("280.00", summary.getNetProfit());

        List<AccountingSummaryResponse.ExpenseCategorySummary> breakdown = summary.getInventoryLossByReason();
        assertEquals(1, breakdown.size());
        assertEquals("SPOILAGE_EXPIRY", breakdown.get(0).getCategory());
        assertMoney("120.00", breakdown.get(0).getAmount());

        List<JournalEntryResponse> entries = glService.journal(start, end);
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)), "GL must balance");
        BigDecimal glExpense = glOperatingExpenseDebit(entries);
        assertMoney("120.00", glExpense);
        assertEquals(0, summary.getTotalExpenses().compareTo(glExpense),
                "GL expense must equal P&L expense for the period (I4)");
        assertEquals(0, summary.getTotalInventoryLoss().compareTo(glExpense));
    }

    @Test
    void p3_countShortageShowsAsShrinkageInPnl() {
        loss(StockMovement.ReferenceType.STOCK_COUNT, "MISCOUNT", "-45.00");

        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = LocalDate.now();
        AccountingSummaryResponse summary = reportService.getAccountingSummary(start, end);

        assertMoney("45.00", summary.getTotalInventoryLoss());
        assertMoney("45.00", summary.getTotalExpenses());
        List<AccountingSummaryResponse.ExpenseCategorySummary> breakdown = summary.getInventoryLossByReason();
        assertEquals(1, breakdown.size());
        assertEquals(InventoryLossService.SHRINKAGE, breakdown.get(0).getCategory());
        assertMoney("45.00", breakdown.get(0).getAmount());

        BigDecimal glExpense = glOperatingExpenseDebit(glService.journal(start, end));
        assertEquals(0, summary.getTotalExpenses().compareTo(glExpense));
    }

    @Test
    void p3_rtvAndRevaluationDoNotInflateOperatingLoss() {
        loss(StockMovement.ReferenceType.STOCK_ADJUSTMENT, "SPOILAGE_EXPIRY", "-120.00");
        loss(StockMovement.ReferenceType.RTV, null, "-50.00");
        StockMovement reval = new StockMovement();
        reval.setId(99L);
        reval.setMovementType(StockMovement.MovementType.REVALUATION);
        reval.setReferenceType(StockMovement.ReferenceType.STOCK_ADJUSTMENT);
        reval.setReasonCode("SPOILAGE_EXPIRY");
        reval.setValuationAmount(new BigDecimal("-20.00"));
        reval.setMovementDate(LocalDateTime.now());
        movements.add(reval);

        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = LocalDate.now();
        AccountingSummaryResponse summary = reportService.getAccountingSummary(start, end);

        // Only the spoilage write-off is an operating loss in the P&L breakdown.
        assertMoney("120.00", summary.getTotalInventoryLoss());
        assertEquals(1, summary.getInventoryLossByReason().size());
        assertEquals("SPOILAGE_EXPIRY", summary.getInventoryLossByReason().get(0).getCategory());
    }
}
