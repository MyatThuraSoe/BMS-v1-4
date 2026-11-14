package com.bms.service;

import com.bms.dto.response.AccountingSummaryResponse;
import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.ArPayment;
import com.bms.entity.Expense;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SaleReturn;
import com.bms.entity.SaleReturnItem;
import com.bms.entity.StockMovement;
import com.bms.entity.User;
import com.bms.repository.ArPaymentRepository;
import com.bms.repository.CashDrawerMovementRepository;
import com.bms.repository.ExpenseRepository;
import com.bms.repository.GlAccountRepository;
import com.bms.repository.GlMappingRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.StockMovementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 acceptance (spec 6.1): one month containing a promotion+tax sale, a
 * partial return, a void, a freight purchase with a partial receipt, an RTV, a
 * purchase reversal, a write-off, a revaluation, expenses (incl. purchase
 * variance) and an AR payment. Asserts I4 over real persistence: the journal
 * balances, GL COGS equals report COGS, and GL expense equals P&L expense.
 *
 * Runs on an isolated in-memory H2 slice (no production data, no web context).
 */
@DataJpaTest
@ContextConfiguration(classes = GlJournalIntegrationTest.JpaConfig.class)
@Import({GlService.class, InventoryLossService.class, ReportService.class, LedgerReadService.class, ShopTimeService.class})
class GlJournalIntegrationTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private GlService glService;
    @Autowired private ReportService reportService;
    @Autowired private GlAccountRepository accountRepository;
    @Autowired private GlMappingRepository mappingRepository;
    @Autowired private PurchaseRepository purchaseRepository;
    @Autowired private SaleRepository saleRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private ExpenseRepository expenseRepository;
    @Autowired private ArPaymentRepository arPaymentRepository;
    @Autowired private SaleReturnRepository saleReturnRepository;
    @Autowired private CashDrawerMovementRepository cashDrawerMovementRepository;

    // ReportService injects these but the profit/accounting summaries never use them.
    @MockBean private CashShiftService cashShiftService;
    @MockBean private CashDrawerSessionService cashDrawerSessionService;

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 30);

    private User user;
    private Product product;

    @BeforeEach
    void seed() {
        glService.ensureDefaults();

        Role role = new Role();
        role.setName(Role.RoleName.ROLE_ADMIN);
        em.persist(role);

        user = new User();
        user.setUsername("gl-integration");
        user.setEmail("gl-integration@example.test");
        user.setPassword("unused-fixture-password");
        user.setFirstName("GL");
        user.setLastName("Integration");
        user.setRole(role);
        em.persist(user);

        product = new Product();
        product.setSku("GL-INT-1");
        product.setName("Integration product");
        product.setUnitPrice(new BigDecimal("100.00"));
        product.setCostPrice(new BigDecimal("60.000000"));
        product.setStockQuantity(100);
        em.persist(product);
        em.flush();
    }

    @Test
    void mixedMonthJournalBalancesAndAgreesWithPnl() {
        // 1. Promotion + tax cash sale: list 200, promo price 160, tax 16, total 176.
        Sale promoted = sale("INV-PROMO", Sale.SaleType.CASH, false,
                new BigDecimal("160.00"), new BigDecimal("16.00"), new BigDecimal("176.00"),
                LocalDateTime.of(2026, 11, 5, 10, 0));
        SaleItem promotedItem = promoted.getItems().get(0);
        promotedItem.setQuantity(2);
        promotedItem.setUnitPrice(new BigDecimal("80.00"));
        promotedItem.setTotalPrice(new BigDecimal("160.00"));
        promotedItem.setTaxAmount(new BigDecimal("16.00"));
        promotedItem.setOriginalUnitPrice(new BigDecimal("100.00"));
        promoted.setPromotionDiscount(new BigDecimal("40.00"));
        em.persist(promoted);

        // 2. Partial return of one unit (tax-inclusive refund 88), cash.
        promotedItem.setQuantityRefunded(1);
        SaleReturn ret = new SaleReturn();
        ret.setSale(promoted);
        ret.setReturnedBy(user);
        ret.setReturnDate(LocalDateTime.of(2026, 11, 10, 12, 0));
        ret.setReason("Customer changed mind");
        ret.setRefundMethod(PaymentMethod.CASH);
        ret.setTotalReturnAmount(new BigDecimal("88.00"));
        SaleReturnItem retItem = new SaleReturnItem();
        retItem.setSaleReturn(ret);
        retItem.setSaleItem(promotedItem);
        retItem.setQuantityReturned(1);
        retItem.setReturnAmount(new BigDecimal("88.00"));
        ret.getItems().add(retItem);
        em.persist(ret);

        // 3. Voided cash sale (sold Nov 6, voided Nov 12).
        Sale voided = sale("INV-VOID", Sale.SaleType.CASH, true,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("110.00"),
                LocalDateTime.of(2026, 11, 6, 9, 0));
        voided.setVoidedAt(LocalDateTime.of(2026, 11, 12, 9, 0));
        voided.setVoidedBy(user.getId());
        voided.setVoidedReason("Duplicate ring-up");
        em.persist(voided);

        // 4. Freight purchase, invoice date Nov 1, partial receipt Nov 8.
        Purchase purchase = new Purchase();
        purchase.setPurchaseNumber("PO-INT-1");
        purchase.setPurchaseDate(START);
        purchase.setSubtotal(new BigDecimal("300.00"));
        purchase.setTaxAmount(BigDecimal.ZERO);
        purchase.setTotalAmount(new BigDecimal("300.00"));
        purchase.setExchangeRate(BigDecimal.ONE);
        purchase.setReceivingStatus(Purchase.ReceivingStatus.PARTIAL);
        purchase.setPaymentStatus(Purchase.PaymentStatus.PENDING);
        purchase.setCreatedBy(user.getId());
        em.persist(purchase);
        PurchaseItem purchaseItem = new PurchaseItem();
        purchaseItem.setPurchase(purchase);
        purchaseItem.setProduct(product);
        purchaseItem.setQuantity(10);
        purchaseItem.setUnitCost(new BigDecimal("60.000000"));
        purchaseItem.setTotalCost(new BigDecimal("600.00"));
        purchaseItem.setReceivedQuantity(5);
        em.persist(purchaseItem);

        StockMovement receipt = movement(StockMovement.ReferenceType.RECEIVING,
                StockMovement.MovementType.IN, 5, "300.000000",
                LocalDateTime.of(2026, 11, 8, 15, 30), purchase.getId());
        receipt.setPurchaseItemId(purchaseItem.getId());
        receipt.setDescription("Partial receipt of PO-INT-1");
        em.persist(receipt);

        // 5. RTV (reduces payable, not expense).
        em.persist(movement(StockMovement.ReferenceType.RTV, StockMovement.MovementType.OUT,
                1, "-60.000000", LocalDateTime.of(2026, 11, 15, 11, 0), purchase.getId()));

        // 6. Purchase reversal (reduces payable, not expense).
        em.persist(movement(StockMovement.ReferenceType.PURCHASE, StockMovement.MovementType.OUT,
                1, "-60.000000", LocalDateTime.of(2026, 11, 16, 11, 0), purchase.getId()));

        // 7. Write-off (operating loss).
        StockMovement writeOff = movement(StockMovement.ReferenceType.STOCK_ADJUSTMENT,
                StockMovement.MovementType.ADJUSTMENT_OUT, 2, "-120.000000",
                LocalDateTime.of(2026, 11, 18, 11, 0), 999L);
        writeOff.setReasonCode("SPOILAGE_EXPIRY");
        em.persist(writeOff);

        // 8. Revaluation (reserve, not operating expense).
        em.persist(movement(StockMovement.ReferenceType.STOCK_ADJUSTMENT,
                StockMovement.MovementType.REVALUATION, 0, "-50.000000",
                LocalDateTime.of(2026, 11, 19, 11, 0), 999L));

        // 9. Expenses: rent + purchase variance (variance posted once, via Expense).
        em.persist(expense(Expense.ExpenseCategory.RENT, "200.00", LocalDate.of(2026, 11, 20)));
        em.persist(expense(Expense.ExpenseCategory.PURCHASE_VARIANCE, "30.00", LocalDate.of(2026, 11, 21)));

        // 10. Credit sale + later AR collection.
        Sale credit = sale("INV-CREDIT", Sale.SaleType.CREDIT, false,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("110.00"),
                LocalDateTime.of(2026, 11, 7, 9, 0));
        credit.setPaymentStatus(Sale.PaymentStatus.UNPAID);
        em.persist(credit);
        ArPayment arPayment = new ArPayment();
        arPayment.setInvoice(credit);
        arPayment.setAmount(new BigDecimal("110.00"));
        arPayment.setPaymentMethod(PaymentMethod.CASH);
        arPayment.setPaymentDate(LocalDateTime.of(2026, 11, 22, 9, 0));
        arPayment.setRecordedBy(user);
        em.persist(arPayment);

        em.flush();
        em.clear();

        List<JournalEntryResponse> entries = glService.journal(START, END);

        // I4: debits equal credits across the whole mixed month.
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)),
                "journal must balance across all event classes");

        // I4: GL COGS equals report COGS (net of returns; voided sale excluded).
        Map<String, Object> profit = reportService.getProfitSummary(START, END);
        BigDecimal reportCogs = (BigDecimal) profit.get("cogs");
        assertEquals(0, netDebit(entries, "COST_OF_GOODS_SOLD").compareTo(reportCogs),
                "GL COGS must equal report COGS");

        // I4: GL expense equals P&L expense (expenses + operating inventory losses;
        // RTV/reversal/revaluation must NOT inflate operating expense).
        AccountingSummaryResponse accounting = reportService.getAccountingSummary(START, END);
        assertEquals(0, netDebit(entries, "OPERATING_EXPENSE").compareTo(accounting.getTotalExpenses()),
                "GL expense must equal P&L expense");

        // P4.5: the receipt posts at its receipt date (Nov 8), not the invoice date.
        JournalEntryResponse receiptEntry = entries.stream()
                .filter(e -> ("MOV-" + receipt.getId()).equals(e.getReference()))
                .filter(e -> "INVENTORY".equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 11, 8), receiptEntry.getDate());
        // P4.3: no duplicate header posting for a purchase covered by movements.
        assertTrue(entries.stream().noneMatch(e -> "PO-INT-1".equals(e.getReference())),
                "purchase with receiving movements must not also post a header entry");
    }

    // ------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------

    private Sale sale(String invoice, Sale.SaleType type, boolean voided,
                      BigDecimal subtotal, BigDecimal tax, BigDecimal total, LocalDateTime date) {
        Sale sale = new Sale();
        sale.setInvoiceNumber(invoice);
        sale.setCustomerDisplayName("Walk-in");
        sale.setCashierId(user.getId());
        sale.setSaleDate(date);
        sale.setSubtotal(subtotal);
        sale.setTaxAmount(tax);
        sale.setTotalAmount(total);
        sale.setAmountPaid(voided || type == Sale.SaleType.CREDIT ? BigDecimal.ZERO : total);
        sale.setSaleType(type);
        sale.setPaymentMethod(type == Sale.SaleType.CREDIT ? Sale.PaymentMethod.CREDIT : Sale.PaymentMethod.CASH);
        sale.setPaymentStatus(type == Sale.SaleType.CREDIT ? Sale.PaymentStatus.UNPAID : Sale.PaymentStatus.PAID);
        sale.setIsVoided(voided);
        SaleItem item = new SaleItem();
        item.setSale(sale);
        item.setProduct(product);
        item.setQuantity(1);
        item.setUnitPrice(subtotal);
        item.setTotalPrice(subtotal);
        item.setTaxAmount(tax);
        item.setCostPriceAtSale(new BigDecimal("60.000000"));
        sale.getItems().add(item);
        return sale;
    }

    private StockMovement movement(StockMovement.ReferenceType reference, StockMovement.MovementType type,
                                   int quantity, String valuation, LocalDateTime date, Long referenceId) {
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(type);
        movement.setQuantity(quantity);
        movement.setReferenceType(reference);
        movement.setReferenceId(referenceId);
        movement.setValuationAmount(new BigDecimal(valuation));
        movement.setMovementDate(date);
        movement.setCreatedBy(user);
        return movement;
    }

    private Expense expense(Expense.ExpenseCategory category, String amount, LocalDate date) {
        Expense expense = new Expense();
        expense.setCategory(category);
        expense.setAmount(new BigDecimal(amount));
        expense.setExpenseDate(date);
        expense.setCreatedBy(user.getId());
        expense.setDescription(category.name());
        return expense;
    }

    private static BigDecimal sumDebits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumCredits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal netDebit(List<JournalEntryResponse> entries, String eventType) {
        BigDecimal debit = entries.stream().filter(e -> eventType.equals(e.getEventType()))
                .map(JournalEntryResponse::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = entries.stream().filter(e -> eventType.equals(e.getEventType()))
                .map(JournalEntryResponse::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return debit.subtract(credit);
    }
}