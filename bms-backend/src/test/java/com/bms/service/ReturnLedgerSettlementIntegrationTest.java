package com.bms.service;

import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.PaymentMethod;
import com.bms.entity.PeriodClose;
import com.bms.entity.Product;
import com.bms.entity.ProfitLedger;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SaleReturn;
import com.bms.entity.SaleReturnItem;
import com.bms.entity.StockMovement;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.ProfitLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N1 acceptance: a return takes money OUT, so every settlement part of a RETURN
 * ledger row is negative (a credit), each row carries only its own line's share,
 * and a store-credit refund reaches a real liability account.
 *
 * Runs on an isolated in-memory H2 slice with ledger reads switched on, so these
 * rows reach the journal through the ledger path rather than the legacy one.
 */
@DataJpaTest
@ContextConfiguration(classes = ReturnLedgerSettlementIntegrationTest.JpaConfig.class)
@Import({GlService.class, LedgerPostingService.class, LedgerHistoryService.class, PeriodGuard.class,
        LedgerReadService.class, InventoryLossService.class, ReportService.class, ShopTimeService.class})
class ReturnLedgerSettlementIntegrationTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private GlService glService;
    @Autowired private LedgerPostingService postingService;
    @Autowired private LedgerHistoryService historyService;
    @Autowired private ProfitLedgerRepository profitLedgerRepository;
    @Autowired private PeriodCloseRepository periodCloseRepository;

    @MockBean private AuditLogService auditLogService;
    @MockBean private CashShiftService cashShiftService;
    @MockBean private CashDrawerSessionService cashDrawerSessionService;
    @MockBean private SystemSettingService systemSettingService;

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 30);
    private static final BigDecimal COST = new BigDecimal("30.000000");

    private User cashier;

    @BeforeEach
    void seedShop() {
        glService.ensureDefaults();
        // The ledger path needs an activated cutover and the period lock row.
        em.persist(setting(LedgerReadService.CUTOVER_KEY, "2026-11-01T00:00:00"));
        em.persist(setting(PeriodGuard.LOCK_KEY, "0"));

        Role role = new Role();
        role.setName(Role.RoleName.ROLE_CASHIER);
        em.persist(role);

        cashier = new User();
        cashier.setUsername("return-cashier");
        cashier.setEmail("return-cashier@example.test");
        cashier.setPassword("unused-fixture-password");
        cashier.setFirstName("Return");
        cashier.setLastName("Cashier");
        cashier.setRole(role);
        em.persist(cashier);
        em.flush();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static SystemSetting setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setDescription("test fixture");
        return setting;
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private Product product(String sku) {
        Product product = new Product();
        product.setSku(sku);
        product.setName("Return fixture " + sku);
        product.setUnitPrice(new BigDecimal("50.00"));
        product.setCostPrice(COST);
        product.setStockQuantity(10);
        return em.persistAndFlush(product);
    }

    /** One line per pair of (net, tax) values, each sold in quantity 1. */
    private Sale sale(String invoice, Sale.SaleType type, List<String> nets, List<String> taxes) {
        Sale sale = new Sale();
        sale.setInvoiceNumber(invoice);
        sale.setCustomerDisplayName("Walk-in");
        sale.setCashierId(cashier.getId());
        sale.setSaleDate(LocalDateTime.of(2026, 11, 5, 10, 0));
        BigDecimal subtotal = nets.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = taxes.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
        sale.setSubtotal(subtotal);
        sale.setTaxAmount(tax);
        sale.setTotalAmount(subtotal.add(tax));
        sale.setAmountPaid(type == Sale.SaleType.CREDIT ? BigDecimal.ZERO : subtotal.add(tax));
        sale.setSaleType(type);
        sale.setPaymentMethod(type == Sale.SaleType.CREDIT ? Sale.PaymentMethod.CREDIT : Sale.PaymentMethod.CASH);
        sale.setPaymentStatus(type == Sale.SaleType.CREDIT ? Sale.PaymentStatus.UNPAID : Sale.PaymentStatus.PAID);
        sale.setIsVoided(false);
        for (int i = 0; i < nets.size(); i++) {
            SaleItem item = new SaleItem();
            item.setSale(sale);
            item.setProduct(product("RET-" + invoice + "-" + i));
            item.setQuantity(1);
            item.setUnitPrice(decimal(nets.get(i)));
            item.setTotalPrice(decimal(nets.get(i)));
            item.setTaxAmount(decimal(taxes.get(i)));
            item.setCostPriceAtSale(COST);
            item.setQuantityRefunded(0);
            sale.getItems().add(item);
        }
        return em.persistAndFlush(sale);
    }

    /** A return of every line of {@code sale} with the amounts the shop decided. */
    private SaleReturn returnAll(Sale sale, PaymentMethod method, String debtReduction, String refundAmount) {
        SaleReturn saleReturn = new SaleReturn();
        saleReturn.setSale(sale);
        saleReturn.setReturnedBy(cashier);
        saleReturn.setReturnDate(LocalDateTime.of(2026, 11, 10, 12, 0));
        saleReturn.setReason("Fixture return");
        saleReturn.setRefundMethod(method);
        saleReturn.setTotalReturnAmount(sumOfLines(sale));
        saleReturn.setDebtReductionAmount(decimal(debtReduction));
        saleReturn.setRefundAmount(decimal(refundAmount));
        for (SaleItem item : sale.getItems()) {
            SaleReturnItem returnItem = new SaleReturnItem();
            returnItem.setSaleReturn(saleReturn);
            returnItem.setSaleItem(item);
            returnItem.setQuantityReturned(item.getQuantity());
            returnItem.setReturnAmount(item.getTotalPrice().add(item.getTaxAmount()));
            saleReturn.getItems().add(returnItem);
            item.setQuantityRefunded(item.getQuantity());
        }
        return em.persistAndFlush(saleReturn);
    }

    private static BigDecimal sumOfLines(Sale sale) {
        return sale.getItems().stream().map(item -> item.getTotalPrice().add(item.getTaxAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Posts every line of the return with its own share, exactly as the service does. */
    private void postReturnLines(SaleReturn saleReturn) {
        BigDecimal total = saleReturn.getTotalReturnAmount();
        BigDecimal debt = saleReturn.getDebtReductionAmount();
        List<SaleReturnItem> lines = saleReturn.getItems();
        List<BigDecimal> lineTotals = lines.stream().map(SaleReturnItem::getReturnAmount).toList();
        List<BigDecimal> lineDebts = SaleReturnService.debtForLines(debt, total, lineTotals);
        for (int i = 0; i < lines.size(); i++) {
            BigDecimal lineTotal = lineTotals.get(i);
            BigDecimal lineDebt = lineDebts.get(i);
            postingService.postReturnProfit(saleReturn, lines.get(i).getSaleItem(),
                    lines.get(i).getQuantityReturned(), 0, lineDebt, lineTotal.subtract(lineDebt));
        }
    }

    /** A RETURN row as PR #18 wrote it: the refund stored as a positive debit. */
    private ProfitLedger legacyReturnRow(SaleReturn saleReturn, SaleItem item) {
        ProfitLedger row = new ProfitLedger();
        row.setPeriod("2026-11");
        row.setEventType(ProfitLedger.EventType.RETURN);
        row.setSourceType("SALE_RETURN");
        row.setSourceId(saleReturn.getId());
        row.setProductId(item.getProduct().getId());
        row.setLineKey(String.valueOf(item.getId()));
        row.setQuantity(-1);
        row.setNetRevenue(decimal("-50.0000"));
        row.setCogs(COST);
        row.setExpense(BigDecimal.ZERO);
        row.setTaxAmount(decimal("-10.0000"));
        row.setSettlementCash(decimal("60.0000"));
        row.setSettlementClearing(BigDecimal.ZERO);
        row.setSettlementAr(BigDecimal.ZERO);
        row.setReference("RET-" + saleReturn.getId());
        row.setOccurredAt(LocalDateTime.of(2026, 11, 10, 12, 0));
        row.setPostedAt(LocalDateTime.of(2026, 11, 10, 12, 0));
        return em.persistAndFlush(row);
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private ProfitLedger returnRow(Long saleReturnId, SaleItem item) {
        Optional<ProfitLedger> row = profitLedgerRepository.findAll().stream()
                .filter(p -> p.getEventType() == ProfitLedger.EventType.RETURN)
                .filter(p -> saleReturnId.equals(p.getSourceId()))
                .filter(p -> String.valueOf(item.getId()).equals(p.getLineKey()))
                .findFirst();
        return row.orElseThrow(() -> new AssertionError("no RETURN ledger row for sale return "
                + saleReturnId + " line " + item.getId()));
    }

    private static void assertDecimal(String expected, BigDecimal actual, String label) {
        assertNotNull(actual, label + " must be written");
        assertEquals(0, decimal(expected).compareTo(actual), label + " was " + actual);
    }

    private List<JournalEntryResponse> journal() {
        List<JournalEntryResponse> entries = glService.journal(START, END);
        assertFalse(entries.isEmpty(), "the ledger path must produce journal entries");
        return entries;
    }

    private static List<JournalEntryResponse> referenceEntries(List<JournalEntryResponse> entries, String reference) {
        List<JournalEntryResponse> found = entries.stream()
                .filter(e -> reference.equals(e.getReference())).toList();
        assertFalse(found.isEmpty(), "no journal entries for reference " + reference + " of "
                + entries.stream().map(JournalEntryResponse::getReference).toList());
        return found;
    }

    private static void assertCredits(List<JournalEntryResponse> entries, String account, String amount,
                                      String reference) {
        assertSide(entries, account, amount, reference, true);
    }

    private static void assertDebts(List<JournalEntryResponse> entries, String account, String amount,
                                    String reference) {
        assertSide(entries, account, amount, reference, false);
    }

    private static void assertSide(List<JournalEntryResponse> entries, String account, String amount,
                                   String reference, boolean credit) {
        List<JournalEntryResponse> onAccount = entries.stream()
                .filter(e -> reference.equals(e.getReference()))
                .filter(e -> account.equals(e.getEventType())).toList();
        assertEquals(1, onAccount.size(), "expected one " + account + " line for " + reference + ": " + entries);
        assertDecimal(amount, credit ? onAccount.get(0).getCredit() : onAccount.get(0).getDebit(),
                account + (credit ? " credit" : " debit"));
        assertDecimal("0", credit ? onAccount.get(0).getDebit() : onAccount.get(0).getCredit(),
                account + (credit ? " debit" : " credit"));
    }

    private static void assertBalanced(List<JournalEntryResponse> entries, String label) {
        BigDecimal debits = entries.stream().map(JournalEntryResponse::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = entries.stream().map(JournalEntryResponse::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, debits.compareTo(credits), label + ": debits " + debits + " vs credits " + credits);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void cashRefundOfOneItemCreditsCashInsteadOfDebitingIt() {
        Sale sale = sale("INV-CASH-1", Sale.SaleType.CASH, List.of("50.00"), List.of("10.00"));
        postingService.postSaleProfit(sale, sale.getItems().get(0));
        SaleReturn saleReturn = returnAll(sale, PaymentMethod.CASH, "0.00", "60.00");

        postReturnLines(saleReturn);

        ProfitLedger row = returnRow(saleReturn.getId(), sale.getItems().get(0));
        assertDecimal("-60.00", row.getSettlementCash(), "a cash refund leaves the till");
        assertDecimal("0", row.getSettlementAr(), "a cash sale carries no debt");
        assertDecimal("0", row.getSettlementClearing(), "no tender clearing on a cash refund");

        List<JournalEntryResponse> entries = journal();
        List<JournalEntryResponse> refund = referenceEntries(entries, "RET-" + saleReturn.getId());
        assertCredits(refund, "CASH", "60.00", "RET-" + saleReturn.getId());
        assertDebts(refund, "SALES_REVENUE", "50.00", "RET-" + saleReturn.getId());
        assertDebts(refund, "SALES_TAX_PAYABLE", "10.00", "RET-" + saleReturn.getId());
        assertBalanced(refund, "the refund posting on its own");
    }

    @Test
    void twoLineReturnSplitsTheRefundInsteadOfPostingItTwice() {
        Sale sale = sale("INV-CASH-2", Sale.SaleType.CASH, List.of("50.00", "30.00"), List.of("10.00", "10.00"));
        sale.getItems().forEach(item -> postingService.postSaleProfit(sale, item));
        SaleReturn saleReturn = returnAll(sale, PaymentMethod.CASH, "0.00", "100.00");

        postReturnLines(saleReturn);

        ProfitLedger first = returnRow(saleReturn.getId(), sale.getItems().get(0));
        ProfitLedger second = returnRow(saleReturn.getId(), sale.getItems().get(1));
        assertDecimal("-60.00", first.getSettlementCash(), "the first line refunds its own 60");
        assertDecimal("-40.00", second.getSettlementCash(), "the second line refunds its own 40");
        assertDecimal("-100.00", first.getSettlementCash().add(second.getSettlementCash()),
                "the return as a whole equals the return total, not double it");
    }

    @Test
    void cardRefundCreditsTenderClearing() {
        Sale sale = sale("INV-CARD-1", Sale.SaleType.CASH, List.of("50.00"), List.of("10.00"));
        postingService.postSaleProfit(sale, sale.getItems().get(0));
        SaleReturn saleReturn = returnAll(sale, PaymentMethod.CARD, "0.00", "60.00");

        postReturnLines(saleReturn);

        ProfitLedger row = returnRow(saleReturn.getId(), sale.getItems().get(0));
        assertDecimal("-60.00", row.getSettlementClearing(), "a card refund goes back through the terminal");
        assertDecimal("0", row.getSettlementCash(), "no cash leaves the drawer for a card refund");

        List<JournalEntryResponse> refund = referenceEntries(journal(), "RET-" + saleReturn.getId());
        assertCredits(refund, "TENDER_CLEARING", "60.00", "RET-" + saleReturn.getId());
        assertBalanced(refund, "the card refund posting");
    }

    @Test
    void storeCreditRefundCreditsTheRefundLiabilityAccount() {
        Sale sale = sale("INV-STORE-1", Sale.SaleType.CASH, List.of("50.00"), List.of("10.00"));
        postingService.postSaleProfit(sale, sale.getItems().get(0));
        SaleReturn saleReturn = returnAll(sale, PaymentMethod.STORE_CREDIT, "0.00", "60.00");

        postReturnLines(saleReturn);

        ProfitLedger row = returnRow(saleReturn.getId(), sale.getItems().get(0));
        assertDecimal("-60.00", row.getSettlementRefundLiability(), "store credit is money the shop owes");
        assertDecimal("0", row.getSettlementCash(), "store credit takes no cash out");

        List<JournalEntryResponse> refund = referenceEntries(journal(), "RET-" + saleReturn.getId());
        assertCredits(refund, "REFUND_LIABILITY", "60.00", "RET-" + saleReturn.getId());
        assertBalanced(refund, "the store-credit posting");
    }

    @Test
    void creditInvoiceReturnReducesTheDebtThenRefundsTheRestInCash() {
        // Invoice 100 paid 80 leaves 20 owed; returning 60 clears that 20 of debt
        // and refunds the remaining 40 in cash.
        Sale sale = sale("INV-CREDIT-1", Sale.SaleType.CREDIT, List.of("50.00"), List.of("10.00"));
        postingService.postSaleProfit(sale, sale.getItems().get(0));
        SaleReturn saleReturn = returnAll(sale, PaymentMethod.CASH, "20.00", "40.00");

        postReturnLines(saleReturn);

        ProfitLedger row = returnRow(saleReturn.getId(), sale.getItems().get(0));
        assertDecimal("-20.00", row.getSettlementAr(), "the unpaid part reduces what the customer owes");
        assertDecimal("-40.00", row.getSettlementCash(), "the paid part comes back as cash");
        assertDecimal("-60.00", row.getSettlementAr().add(row.getSettlementCash()),
                "both parts together equal the row");

        List<JournalEntryResponse> refund = referenceEntries(journal(), "RET-" + saleReturn.getId());
        assertCredits(refund, "ACCOUNTS_RECEIVABLE", "20.00", "RET-" + saleReturn.getId());
        assertCredits(refund, "CASH", "40.00", "RET-" + saleReturn.getId());
        assertBalanced(refund, "the mixed debt and cash refund");
    }

    @Test
    void aSettlementThatDoesNotMatchTheRowIsRefused() {
        Sale sale = sale("INV-BAD-1", Sale.SaleType.CASH, List.of("50.00"), List.of("10.00"));
        SaleReturn saleReturn = returnAll(sale, PaymentMethod.CASH, "0.00", "60.00");

        // The row reverses 60 of value, so handing it a 20 settlement must not pass.
        BusinessException failure = assertThrows(BusinessException.class,
                () -> postingService.postReturnProfit(saleReturn, sale.getItems().get(0), 1, 0,
                        decimal("10.00"), decimal("10.00")));

        assertTrue(failure.getMessage().toLowerCase().contains("settlement"),
                "the guard must say what did not agree: " + failure.getMessage());
    }

    @Test
    void salesReturnsAndVoidsBalanceAcrossTheMonth() {
        Sale cashSale = sale("INV-MIX-1", Sale.SaleType.CASH, List.of("50.00"), List.of("10.00"));
        cashSale.getItems().forEach(item -> postingService.postSaleProfit(cashSale, item));
        cashSale.setIsVoided(true);
        cashSale.setVoidedAt(LocalDateTime.of(2026, 11, 20, 9, 0));
        em.flush();
        postingService.postVoidProfit(cashSale, cashSale.getItems().get(0), 1, cashSale.getVoidedAt());

        Sale mixedSale = sale("INV-MIX-2", Sale.SaleType.CREDIT, List.of("50.00", "30.00"),
                List.of("10.00", "10.00"));
        mixedSale.getItems().forEach(item -> postingService.postSaleProfit(mixedSale, item));
        em.flush();
        SaleReturn storeCreditReturn = returnAll(mixedSale, PaymentMethod.STORE_CREDIT, "50.00", "50.00");
        postReturnLines(storeCreditReturn);

        assertBalanced(journal(), "a month of sales, a void and a two-line refund");
    }

    /**
     * Spec 6 final check: a count that found stock posts an INVENTORY_GAIN row, and the month
     * it lands in still has to reconcile. The history only ever named shortages and
     * revaluations, so a gain posting matched nothing and the shop was told its books were
     * wrong for counting correctly.
     */
    @Test
    void aCountThatFoundStockReconcilesAgainstItsGainPosting() {
        StockMovement movement = new StockMovement();
        movement.setProduct(product("GAIN-1"));
        movement.setMovementType(StockMovement.MovementType.ADJUSTMENT_IN);
        movement.setReferenceType(StockMovement.ReferenceType.STOCK_COUNT);
        movement.setReferenceId(1L);
        movement.setQuantity(3);
        // A counter's own reason code is not one of the positive adjustment ones, so it is
        // the STOCK_COUNT reference that makes this a gain rather than an adjustment guess.
        movement.setReasonCode("MISCOUNT");
        movement.setCreatedBy(cashier);
        movement.setValuationAmount(decimal("90.00"));
        movement.setMovementDate(LocalDateTime.of(2026, 11, 7, 9, 0));
        em.persistAndFlush(movement);

        postingService.postInventoryGain(movement);
        em.flush();

        List<Map<String, Object>> issues =
                (List<Map<String, Object>>) historyService.reconcile(YearMonth.of(2026, 11),
                        YearMonth.of(2026, 11)).get("issues");
        assertTrue(issues.stream().noneMatch(row -> "STOCK_MOVEMENT".equals(row.get("source"))),
                "the gain posting needs a historical source it can be matched to: " + issues);
    }

    @Test
    void repairRewritesLegacySignedReturnRows() {
        Sale sale = sale("INV-LEGACY-1", Sale.SaleType.CASH, List.of("50.00"), List.of("10.00"));
        postingService.postSaleProfit(sale, sale.getItems().get(0));
        SaleReturn saleReturn = returnAll(sale, PaymentMethod.CASH, "0.00", "60.00");
        ProfitLedger legacy = legacyReturnRow(saleReturn, sale.getItems().get(0));

        Map<String, Object> result = historyService.repairReturnSettlements(true, cashier.getId());

        assertEquals(1, ((Number) result.get("repaired")).intValue(), "the mis-signed row must be rewritten: " + result);
        assertDecimal("-60.00", profitLedgerRepository.findById(legacy.getId()).orElseThrow().getSettlementCash(),
                "after the repair the refund is a credit");
        assertEquals(0, ((Number) historyService.repairReturnSettlements(true, cashier.getId())
                .get("repaired")).intValue(), "a second run has nothing left to repair");
        assertBalanced(journal(), "the repaired month");
    }

    @Test
    void repairSkipsRowsInClosedPeriodsAndReportsThem() {
        Sale sale = sale("INV-LEGACY-2", Sale.SaleType.CASH, List.of("50.00"), List.of("10.00"));
        postingService.postSaleProfit(sale, sale.getItems().get(0));
        SaleReturn saleReturn = returnAll(sale, PaymentMethod.CASH, "0.00", "60.00");
        ProfitLedger legacy = legacyReturnRow(saleReturn, sale.getItems().get(0));

        PeriodClose closed = new PeriodClose();
        closed.setPeriod(legacy.getPeriod());
        closed.setClosedAt(LocalDateTime.of(2026, 12, 1, 0, 0));
        closed.setClosedBy(cashier.getId());
        periodCloseRepository.save(closed);
        periodCloseRepository.flush();

        Map<String, Object> result = historyService.repairReturnSettlements(true, cashier.getId());

        assertEquals(0, ((Number) result.get("repaired")).intValue(),
                "a closed period must not be rewritten: " + result);
        assertTrue(String.valueOf(result.get("skippedClosedPeriods")).contains(legacy.getPeriod()),
                "skipped closed periods must be listed: " + result);
        assertDecimal("60.00", profitLedgerRepository.findById(legacy.getId()).orElseThrow().getSettlementCash(),
                "the frozen row keeps its stored value");
    }

    @Test
    void repairRefusesWithoutABackupConfirmation() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> historyService.repairReturnSettlements(false, cashier.getId()));
        assertTrue(failure.getMessage().toLowerCase().contains("backup"), failure.getMessage());
    }
}
