package com.bms.service;

import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.GlAccount;
import com.bms.entity.GlMapping;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.StockMovement;
import com.bms.repository.GlAccountRepository;
import com.bms.repository.GlMappingRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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

    private GlService glService;
    private final List<Sale> journalSales = new ArrayList<>();

    @BeforeEach
    void setUp() {
        glService = new GlService(accountRepository, mappingRepository,
                purchaseRepository, saleRepository, stockMovementRepository,
                new InventoryLossService(stockMovementRepository));

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
        lenient().when(saleRepository.findForJournal(any(), any())).thenReturn(journalSales);
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

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
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
}