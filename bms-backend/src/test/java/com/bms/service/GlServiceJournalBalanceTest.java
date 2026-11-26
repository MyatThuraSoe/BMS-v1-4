package com.bms.service;

import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.ArPayment;
import com.bms.entity.GlAccount;
import com.bms.entity.GlMapping;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SalePayment;
import com.bms.entity.SaleReturn;
import com.bms.entity.SaleReturnItem;
import com.bms.entity.StockMovement;
import com.bms.entity.Supplier;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * P4.1: the journal's SALES_DISCOUNT debit must be the header discount only.
 * Promotion / price-list discounts are already baked into the stored line
 * prices (subtotal), so adding them again unbalances the journal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GlServiceJournalBalanceTest {

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
    private final List<Sale> journalSales = new ArrayList<>();

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
        lenient().when(purchasePaymentRepository.findForJournal(any(), any())).thenReturn(List.of());

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
        lenient().when(stockMovementRepository.findForJournal(any(), any())).thenReturn(List.of());
        lenient().when(stockMovementRepository.findByReference(any(), any())).thenReturn(List.of());
        lenient().when(saleRepository.findForJournal(any(), any())).thenReturn(journalSales);
        lenient().when(saleRepository.findVoidedForJournal(any(), any())).thenReturn(List.of());
        lenient().when(saleReturnRepository.findForJournal(any(), any())).thenReturn(List.of());
        lenient().when(saleReturnRepository.findBySaleIdOrderByReturnDateDesc(any())).thenReturn(List.of());
        lenient().when(arPaymentRepository.findForJournal(any(), any())).thenReturn(List.of());
        lenient().when(expenseRepository.findFiltered(any(), any(), any())).thenReturn(List.of());
        lenient().when(cashDrawerMovementRepository.findForJournal(any(), any())).thenReturn(List.of());
    }

    /** Builds a sale whose stored values follow the corrected P5 semantics. */
    private Sale sale(String invoice, BigDecimal promoDiscount, BigDecimal priceListDiscount,
                      BigDecimal headerDiscount, BigDecimal taxRatePercent) {
        BigDecimal listValue = new BigDecimal("100.00");
        BigDecimal subtotal = listValue.subtract(promoDiscount).subtract(priceListDiscount)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = subtotal.subtract(headerDiscount)
                .multiply(taxRatePercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.subtract(headerDiscount).add(tax);

        Sale sale = new Sale();
        sale.setId((long) (journalSales.size() + 1));
        sale.setInvoiceNumber(invoice);
        sale.setSaleDate(LocalDateTime.now());
        sale.setSaleType(Sale.SaleType.CASH);
        sale.setSubtotal(subtotal);
        sale.setDiscountAmount(headerDiscount);
        sale.setPromotionDiscount(promoDiscount);
        sale.setPriceListDiscount(priceListDiscount);
        sale.setTaxAmount(tax);
        sale.setTotalAmount(total);

        SaleItem item = new SaleItem();
        item.setSale(sale);
        item.setQuantity(1);
        item.setUnitPrice(subtotal);
        item.setTotalPrice(subtotal);
        item.setTaxAmount(tax);
        item.setCostPriceAtSale(new BigDecimal("60.00"));
        sale.getItems().add(item);

        journalSales.add(sale);
        return sale;
    }

    private List<JournalEntryResponse> entriesFor(String invoice) {
        List<JournalEntryResponse> entries = glService.journal(
                LocalDate.now().withDayOfMonth(1), LocalDate.now());
        List<JournalEntryResponse> filtered = new ArrayList<>();
        for (JournalEntryResponse entry : entries) {
            if (invoice.equals(entry.getReference())) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    private static BigDecimal sumDebits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumCredits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal eventDebit(List<JournalEntryResponse> entries, String eventType) {
        return entries.stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .map(JournalEntryResponse::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal eventCredit(List<JournalEntryResponse> entries, String eventType) {
        return entries.stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .map(JournalEntryResponse::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal descriptionCredit(List<JournalEntryResponse> entries, String description,
                                                String eventType) {
        return entries.stream()
                .filter(e -> description.equals(e.getDescription()) && eventType.equals(e.getEventType()))
                .map(JournalEntryResponse::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    private static void addPayment(Sale sale, PaymentMethod method, String amount) {
        SalePayment payment = new SalePayment();
        payment.setSale(sale);
        payment.setPaymentMethod(method);
        payment.setAmount(new BigDecimal(amount));
        sale.getPayments().add(payment);
    }

    @Test
    void f23_cardSaleSettlesToClearingInsteadOfCash() {
        Sale sale = sale("INV-CARD", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        addPayment(sale, PaymentMethod.CARD, "100.00");

        List<JournalEntryResponse> entries = entriesFor("INV-CARD");
        assertMoney("0.00", eventDebit(entries, "CASH"));
        assertMoney("100.00", eventDebit(entries, "TENDER_CLEARING"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
    }

    @Test
    void f23_mixedSaleSplitsSettlementAcrossCashAndClearing() {
        Sale sale = sale("INV-MIXED", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        addPayment(sale, PaymentMethod.CASH, "60.00");
        addPayment(sale, PaymentMethod.CARD, "40.00");

        List<JournalEntryResponse> entries = entriesFor("INV-MIXED");
        assertMoney("60.00", eventDebit(entries, "CASH"));
        assertMoney("40.00", eventDebit(entries, "TENDER_CLEARING"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
    }

    @Test
    void f23_cardRefundCreditsClearing() {
        Sale sale = sale("INV-REFUND", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        SaleReturn refund = new SaleReturn();
        refund.setId(8L);
        refund.setSale(sale);
        refund.setReturnDate(LocalDateTime.now());
        refund.setTotalReturnAmount(new BigDecimal("100.00"));
        refund.setRefundMethod(PaymentMethod.CARD);
        lenient().when(saleReturnRepository.findForJournal(any(), any())).thenReturn(List.of(refund));

        List<JournalEntryResponse> entries = entriesFor("RET-8");
        assertMoney("100.00", eventCredit(entries, "TENDER_CLEARING"));
        assertMoney("0.00", eventCredit(entries, "CASH"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
    }

    @Test
    void f23_voidOfMixedSaleReversesOriginalTenderProportions() {
        Sale sale = sale("INV-VOID-MIXED", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        addPayment(sale, PaymentMethod.CASH, "60.00");
        addPayment(sale, PaymentMethod.CARD, "40.00");
        sale.setVoidedAt(LocalDateTime.now());
        lenient().when(saleRepository.findVoidedForJournal(any(), any())).thenReturn(List.of(sale));

        List<JournalEntryResponse> entries = entriesFor("INV-VOID-MIXED");
        assertMoney("60.00", descriptionCredit(entries, "Void reversal", "CASH"));
        assertMoney("40.00", descriptionCredit(entries, "Void reversal", "TENDER_CLEARING"));
    }

    @Test
    void t6_voidReversalDoesNotTurnTheCreditDebtRowIntoRefundLiability() {
        Sale sale = sale("INV-VOID-CREDIT-DEBT", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
        sale.setSaleType(Sale.SaleType.CREDIT);
        sale.setPaymentMethod(Sale.PaymentMethod.CREDIT);
        addPayment(sale, PaymentMethod.CREDIT, "100.00");

        ArPayment collectedCash = new ArPayment();
        collectedCash.setInvoice(sale);
        collectedCash.setAmount(new BigDecimal("30.00"));
        collectedCash.setPaymentMethod(PaymentMethod.CASH);
        collectedCash.setPaymentDate(LocalDateTime.now());
        lenient().when(arPaymentRepository.findByInvoiceIdOrderByPaymentDateAsc(sale.getId()))
                .thenReturn(List.of(collectedCash));

        sale.setVoidedAt(LocalDateTime.now());
        lenient().when(saleRepository.findVoidedForJournal(any(), any())).thenReturn(List.of(sale));

        List<JournalEntryResponse> entries = entriesFor("INV-VOID-CREDIT-DEBT");
        assertMoney("0.00", descriptionCredit(entries, "Void reversal", "REFUND_LIABILITY"));
        assertMoney("70.00", descriptionCredit(entries, "Void reversal", "ACCOUNTS_RECEIVABLE"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)),
                "void reversal must balance: " + entries);
    }

    @Test
    void p41_promotionOnlySaleBalances() {
        // Acceptance: list 100, promo price 80, cost 60, no tax.
        sale("INV-PROMO", new BigDecimal("20.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        List<JournalEntryResponse> entries = entriesFor("INV-PROMO");
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)),
                "debits must equal credits for a promoted sale");
        assertMoney("80.00", eventCredit(entries, "SALES_REVENUE"));
        assertMoney("0.00", eventDebit(entries, "SALES_DISCOUNT"));
        assertMoney("80.00", eventDebit(entries, "CASH"));
        assertMoney("60.00", eventDebit(entries, "COST_OF_GOODS_SOLD"));
        assertMoney("60.00", eventCredit(entries, "INVENTORY"));
    }

    @Test
    void p41_discountDebitIsHeaderDiscountOnly() {
        sale("INV-MIX", new BigDecimal("20.00"), new BigDecimal("5.00"),
                new BigDecimal("10.00"), BigDecimal.TEN);

        List<JournalEntryResponse> entries = entriesFor("INV-MIX");
        assertMoney("10.00", eventDebit(entries, "SALES_DISCOUNT"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
    }

    @Test
    void p41_everyDiscountAndTaxCombinationBalances() {
        BigDecimal[] promos = {BigDecimal.ZERO, new BigDecimal("20.00")};
        BigDecimal[] priceLists = {BigDecimal.ZERO, new BigDecimal("5.00")};
        BigDecimal[] headers = {BigDecimal.ZERO, new BigDecimal("10.00")};
        BigDecimal[] taxRates = {BigDecimal.ZERO, BigDecimal.TEN};

        int i = 0;
        for (BigDecimal promo : promos) {
            for (BigDecimal priceList : priceLists) {
                for (BigDecimal header : headers) {
                    for (BigDecimal taxRate : taxRates) {
                        sale("INV-C" + (i++), promo, priceList, header, taxRate);
                    }
                }
            }
        }

        List<JournalEntryResponse> all = glService.journal(
                LocalDate.now().withDayOfMonth(1), LocalDate.now());
        assertEquals(0, sumDebits(all).compareTo(sumCredits(all)),
                "journal must balance across all discount/tax combinations");

        for (Sale built : journalSales) {
            List<JournalEntryResponse> entries = entriesFor(built.getInvoiceNumber());
            assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)),
                    "unbalanced journal for " + built.getInvoiceNumber());
            assertEquals(0, built.getDiscountAmount()
                            .compareTo(eventDebit(entries, "SALES_DISCOUNT")),
                    "discount debit must equal header discount for " + built.getInvoiceNumber());
            assertEquals(0, built.getSubtotal()
                            .compareTo(eventCredit(entries, "SALES_REVENUE")),
                    "revenue credit must equal stored subtotal for " + built.getInvoiceNumber());
            assertEquals(0, built.getTaxAmount()
                            .compareTo(eventCredit(entries, "SALES_TAX_PAYABLE")),
                    "tax credit must equal stored tax for " + built.getInvoiceNumber());
            assertEquals(0, built.getTotalAmount().compareTo(eventDebit(entries, "CASH")),
                    "cash debit must equal invoice total for " + built.getInvoiceNumber());
        }
    }

    @Test
    void p42_saleBooksCogsAtTheFullSoldQuantityRegardlessOfLaterRefunds() {
        // GL/P&L divergence: quantityRefunded is mutable. Posting net-of-refunds
        // on the sale date rewrites an earlier month every time a later return
        // happens, so the sale month can never be frozen.
        Sale sale = sale("INV-RETURNED", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        SaleItem item = sale.getItems().get(0);
        item.setQuantity(3);
        item.setQuantityRefunded(1);
        item.setCostPriceAtSale(new BigDecimal("0.337835"));

        List<JournalEntryResponse> entries = entriesFor(sale.getInvoiceNumber());

        assertMoney("1.013505", eventDebit(entries, "COST_OF_GOODS_SOLD"));
        assertMoney("1.013505", eventCredit(entries, "INVENTORY"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
    }

    @Test
    void glp_returnReversesCogsOnItsOwnDateInsteadOfDoubleCounting() {
        Sale sale = sale("INV-RETCOGS", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        SaleItem item = sale.getItems().get(0);
        item.setQuantity(3);
        item.setCostPriceAtSale(new BigDecimal("10.00"));
        SaleReturn ret = saleReturn(77L, sale, item, 1, item);
        lenient().when(saleReturnRepository.findForJournal(any(), any())).thenReturn(List.of(ret));

        List<JournalEntryResponse> entries = glService.journal(
                LocalDate.now().withDayOfMonth(1), LocalDate.now());

        // Sale date: all 3 units (30). Return date: 1 unit back (10).
        assertMoney("30.00", eventDebit(entries, "COST_OF_GOODS_SOLD"));
        assertMoney("10.00", eventCredit(entries, "COST_OF_GOODS_SOLD"));
        assertMoney("10.00", eventDebit(entries, "INVENTORY"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
    }

    @Test
    void glp_voidReversesOnlyTheCogsTheReturnsDidNotAlreadyReverse() {
        Sale sale = sale("INV-VOIDCOGS", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        SaleItem item = sale.getItems().get(0);
        item.setQuantity(3);
        item.setQuantityRefunded(1);
        item.setCostPriceAtSale(new BigDecimal("10.00"));
        sale.setVoidedAt(LocalDateTime.now());
        lenient().when(saleRepository.findVoidedForJournal(any(), any())).thenReturn(List.of(sale));

        List<JournalEntryResponse> entries = entriesFor("INV-VOIDCOGS");

        // Original 30 debit; void reverses the 2 units no return took back.
        assertMoney("30.00", eventDebit(entries, "COST_OF_GOODS_SOLD"));
        assertMoney("20.00", eventCredit(entries, "COST_OF_GOODS_SOLD"));
        assertMoney("20.00", eventDebit(entries, "INVENTORY"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
    }

    @Test
    void glp_saleInMonthA_returnInMonthB_monthAUnchangedBothModes() {
        // Sale in January: 3 units @ 10 = COGS 30 booked on the sale date.
        Sale sale = sale("INV-JAN", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        SaleItem item = sale.getItems().get(0);
        item.setQuantity(3);
        item.setCostPriceAtSale(new BigDecimal("10.00"));
        sale.setSaleDate(java.time.LocalDateTime.of(2026, 1, 15, 10, 0));

        // Return in February: 1 unit back, COGS reversal booked on the return date.
        SaleReturn ret = saleReturn(77L, sale, item, 1, item);
        ret.setReturnDate(java.time.LocalDateTime.of(2026, 2, 15, 10, 0));
        lenient().when(saleReturnRepository.findForJournal(any(), any())).thenReturn(List.of(ret));

        List<JournalEntryResponse> all = glService.journal(
                java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 2, 28));

        // Sale entry (January): full COGS debit, no credit.
        List<JournalEntryResponse> saleEntries = filterByReference(all, "INV-JAN");
        assertMoney("30.00", eventDebit(saleEntries, "COST_OF_GOODS_SOLD"));
        assertMoney("0.00", eventCredit(saleEntries, "COST_OF_GOODS_SOLD"));

        // Return entry (February): COGS credit for the returned unit, no debit.
        List<JournalEntryResponse> returnEntries = filterByReference(all, "RET-77");
        assertMoney("0.00", eventDebit(returnEntries, "COST_OF_GOODS_SOLD"));
        assertMoney("10.00", eventCredit(returnEntries, "COST_OF_GOODS_SOLD"));

        // The sale entry is unchanged by the return — month A COGS is frozen.
        assertMoney("30.00", eventDebit(saleEntries, "COST_OF_GOODS_SOLD"));
        assertMoney("0.00", eventCredit(saleEntries, "COST_OF_GOODS_SOLD"));
    }

    private static List<JournalEntryResponse> filterByReference(List<JournalEntryResponse> entries, String ref) {
        List<JournalEntryResponse> filtered = new ArrayList<>();
        for (JournalEntryResponse e : entries) {
            if (ref.equals(e.getReference())) {
                filtered.add(e);
            }
        }
        return filtered;
    }

    @Test
    void glp_missingCostAtSaleFailsTheJournalInsteadOfPostingZeroCogs() {
        Sale sale = sale("INV-NOCOST", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        sale.getItems().get(0).setCostPriceAtSale(null);

        org.junit.jupiter.api.Assertions.assertThrows(com.bms.exception.BusinessException.class,
                () -> glService.journal(LocalDate.now().withDayOfMonth(1), LocalDate.now()));
    }

    private SaleReturn saleReturn(long id, Sale sale, SaleItem item, int quantity, SaleItem costSource) {
        SaleReturn ret = new SaleReturn();
        ret.setId(id);
        ret.setSale(sale);
        ret.setReturnDate(LocalDateTime.now());
        ret.setTotalReturnAmount(item.getUnitPrice().multiply(BigDecimal.valueOf(quantity)));
        ret.setRefundMethod(PaymentMethod.CASH);
        SaleReturnItem returned = new SaleReturnItem();
        returned.setSaleReturn(ret);
        returned.setSaleItem(costSource);
        returned.setQuantityReturned(quantity);
        returned.setReturnAmount(ret.getTotalReturnAmount());
        ret.getItems().add(returned);
        return ret;
    }

    @Test
    void p43_purchaseReversalReducesPayableRatherThanOperatingExpense() {
        journalMovement(StockMovement.ReferenceType.PURCHASE, StockMovement.MovementType.OUT, "-50.00");

        List<JournalEntryResponse> entries = glService.journal(LocalDate.now(), LocalDate.now());

        assertMoney("50.00", eventDebit(entries, "ACCOUNTS_PAYABLE"));
        assertMoney("50.00", eventCredit(entries, "INVENTORY"));
        assertMoney("0.00", eventDebit(entries, "OPERATING_EXPENSE"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
    }

    @Test
    void p44_rtvReducesPayableRatherThanOperatingExpense() {
        journalMovement(StockMovement.ReferenceType.RTV, StockMovement.MovementType.OUT, "-300.00");

        List<JournalEntryResponse> entries = glService.journal(LocalDate.now(), LocalDate.now());

        assertMoney("300.00", eventDebit(entries, "ACCOUNTS_PAYABLE"));
        assertMoney("300.00", eventCredit(entries, "INVENTORY"));
        assertMoney("0.00", eventDebit(entries, "OPERATING_EXPENSE"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
    }

    @Test
    void p45_receiptUsesItsDatedValuationNotTheWholePurchaseTotal() {
        StockMovement receipt = journalMovement(StockMovement.ReferenceType.RECEIVING,
                StockMovement.MovementType.IN, "630.123456");
        receipt.setMovementDate(LocalDate.now().atTime(15, 30));

        List<JournalEntryResponse> entries = glService.journal(LocalDate.now(), LocalDate.now());

        assertMoney("630.123456", eventDebit(entries, "INVENTORY"));
        assertMoney("630.123456", eventCredit(entries, "ACCOUNTS_PAYABLE"));
        assertMoney("0", eventCredit(entries, "OPERATING_EXPENSE"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
        entries.forEach(entry -> assertEquals(receipt.getMovementDate().toLocalDate(), entry.getDate()));
    }

    @Test
    void p45_revaluationDoesNotBecomeAnOperatingLoss() {
        journalMovement(StockMovement.ReferenceType.STOCK_ADJUSTMENT,
                StockMovement.MovementType.REVALUATION, "-500.00");

        List<JournalEntryResponse> entries = glService.journal(LocalDate.now(), LocalDate.now());

        assertMoney("500.00", eventDebit(entries, "INVENTORY_REVALUATION"));
        assertMoney("500.00", eventCredit(entries, "INVENTORY"));
        assertMoney("0", eventDebit(entries, "OPERATING_EXPENSE"));
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
    }

    @Test
    void p4_missingMappingFailsInsteadOfReturningAnUnbalancedJournal() {
        sale("INV-MISSING", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        GlAccount account = new GlAccount();
        account.setCode("1000");
        account.setName("Cash");
        GlMapping mapping = new GlMapping();
        mapping.setEventType(GlMapping.EventType.CASH);
        mapping.setAccount(account);
        lenient().when(mappingRepository.findAll()).thenReturn(List.of(mapping));

        org.junit.jupiter.api.Assertions.assertThrows(com.bms.exception.BusinessException.class,
                () -> glService.journal(LocalDate.now(), LocalDate.now()));
    }

    private StockMovement journalMovement(StockMovement.ReferenceType reference,
                                          StockMovement.MovementType type, String valuation) {
        StockMovement movement = new StockMovement();
        movement.setId(600L);
        movement.setReferenceType(reference);
        movement.setMovementType(type);
        movement.setValuationAmount(new BigDecimal(valuation));
        movement.setMovementDate(LocalDateTime.now());
        lenient().when(stockMovementRepository.findForJournal(any(), any())).thenReturn(List.of(movement));
        return movement;
    }

    /** P3 acceptance: write off 12 expired units @ 10 -> GL expense 120, balanced. */
    @Test
    void p3_writeOffBooksOperatingExpenseAndBalances() {
        StockMovement writeOff = new StockMovement();
        writeOff.setId(500L);
        writeOff.setMovementType(StockMovement.MovementType.ADJUSTMENT_OUT);
        writeOff.setReferenceType(StockMovement.ReferenceType.STOCK_ADJUSTMENT);
        writeOff.setReasonCode("SPOILAGE_EXPIRY");
        writeOff.setValuationAmount(new BigDecimal("-120.00"));
        writeOff.setMovementDate(LocalDateTime.now());
        writeOff.setDescription("Write off 12 expired units @ 10");
        lenient().when(stockMovementRepository.findForJournal(any(), any()))
                .thenReturn(List.of(writeOff));

        List<JournalEntryResponse> entries = glService.journal(
                LocalDate.now().withDayOfMonth(1), LocalDate.now());

        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)),
                "write-off journal must balance");
        assertMoney("120.00", eventDebit(entries, "OPERATING_EXPENSE"));
        assertMoney("120.00", eventCredit(entries, "INVENTORY"));
        assertMoney("0.00", eventCredit(entries, "OPERATING_EXPENSE"));
    }

    /** P3 / D6: a physical count shortage is shrinkage expense, not a neutral correction. */
    @Test
    void p3_countShortageBooksShrinkageExpenseEvenWhenReasonIsMiscount() {
        StockMovement shortage = new StockMovement();
        shortage.setId(501L);
        shortage.setMovementType(StockMovement.MovementType.ADJUSTMENT_OUT);
        shortage.setReferenceType(StockMovement.ReferenceType.STOCK_COUNT);
        shortage.setReasonCode("MISCOUNT");
        shortage.setValuationAmount(new BigDecimal("-45.00"));
        shortage.setMovementDate(LocalDateTime.now());
        lenient().when(stockMovementRepository.findForJournal(any(), any()))
                .thenReturn(List.of(shortage));

        List<JournalEntryResponse> entries = glService.journal(
                LocalDate.now().withDayOfMonth(1), LocalDate.now());

        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)));
        assertMoney("45.00", eventDebit(entries, "OPERATING_EXPENSE"));
        assertMoney("45.00", eventCredit(entries, "INVENTORY"));
    }

    /**
     * Defect 3: with the legacy path on, a received purchase item must post an
     * INVENTORY debit and an ACCOUNTS_PAYABLE credit. The removed
     * "alreadyJournaled" dedup previously skipped every received item because
     * receiving movements are persisted to the DB.
     */
    @Test
    void legacyJournalPostsPurchaseGoodsForReceivedItem() {
        Supplier supplier = new Supplier();
        supplier.setId(1L);
        supplier.setName("SUP-1");
        PurchaseItem it = new PurchaseItem();
        it.setId(1L);
        it.setUnitCost(new BigDecimal("100.00"));
        it.setQuantity(10);
        it.setReceivedQuantity(4); // 4 of 10 received -> 400.00 of goods value
        Product prod = new Product();
        prod.setId(100L);
        prod.setWeight(BigDecimal.ONE);
        it.setProduct(prod);
        Purchase po = new Purchase();
        po.setId(1L);
        po.setPurchaseNumber("PO-1");
        po.setSupplier(supplier);
        po.setPurchaseDate(LocalDate.of(2026, 1, 10));
        po.setReceivingStatus(Purchase.ReceivingStatus.PARTIAL);
        po.setSubtotal(new BigDecimal("1000.00"));
        po.setTotalAmount(new BigDecimal("1000.00"));
        po.setExchangeRate(BigDecimal.ONE);
        po.setItems(List.of(it));

        lenient().when(purchaseRepository.findForJournal(any(), any())).thenReturn(List.of(po));

        List<JournalEntryResponse> entries = glService.journal(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        JournalEntryResponse inventory = findEntry(entries, "INVENTORY");
        JournalEntryResponse payable = findEntry(entries, "ACCOUNTS_PAYABLE");
        assertEquals(new BigDecimal("400.00"), inventory.getDebit(),
                "received goods value must debit inventory");
        assertEquals(new BigDecimal("400.00"), payable.getCredit(),
                "received goods value must credit the supplier payable");
    }

    private JournalEntryResponse findEntry(List<JournalEntryResponse> entries, String accountCode) {
        for (JournalEntryResponse e : entries) {
            if (accountCode.equals(e.getAccountCode())) {
                return e;
            }
        }
        throw new IllegalStateException("no journal entry for account " + accountCode);
    }
}