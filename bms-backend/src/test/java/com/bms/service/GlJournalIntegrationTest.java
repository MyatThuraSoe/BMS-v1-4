package com.bms.service;

import com.bms.dto.response.AccountingSummaryResponse;
import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.ArPayment;
import com.bms.entity.CashDrawer;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.DrawerMovementType;
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
 * partial return, a multi-item return, a card refund, a store-credit refund, a
 * void, a freight purchase with a partial receipt, an RTV, a purchase reversal,
 * a write-off, a revaluation, expenses (incl. purchase variance) and an AR
 * payment. Asserts I4 over real persistence: the journal balances, GL COGS
 * equals report COGS, and GL expense equals P&L expense.
 *
 * Also asserts the spec 5 final check on the same month: the cash account
 * equals the drawer's expected cash, and refunds paid by card or store credit
 * stay out of both.
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
    @MockBean private SystemSettingService systemSettingService;

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 30);

    private User user;
    private Product product;
    private Product secondProduct;
    private CashDrawerSession drawerSession;

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

        secondProduct = new Product();
        secondProduct.setSku("GL-INT-2");
        secondProduct.setName("Second integration product");
        secondProduct.setUnitPrice(new BigDecimal("50.00"));
        secondProduct.setCostPrice(new BigDecimal("20.000000"));
        secondProduct.setStockQuantity(100);
        em.persist(secondProduct);
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

        // 11. Multi-item return: two lines of a two-product cash sale come back in
        // one refund, so the till pays once for two revenue lines.
        Sale multi = sale("INV-MULTI", Sale.SaleType.CASH, false,
                new BigDecimal("500.00"), new BigDecimal("50.00"), new BigDecimal("550.00"),
                LocalDateTime.of(2026, 11, 12, 10, 0));
        SaleItem multiFirst = multi.getItems().get(0);
        multiFirst.setQuantity(4);
        multiFirst.setTotalPrice(new BigDecimal("400.00"));
        multiFirst.setTaxAmount(new BigDecimal("40.00"));
        SaleItem multiSecond = new SaleItem();
        multiSecond.setSale(multi);
        multiSecond.setProduct(secondProduct);
        multiSecond.setQuantity(2);
        multiSecond.setUnitPrice(new BigDecimal("50.00"));
        multiSecond.setTotalPrice(new BigDecimal("100.00"));
        multiSecond.setTaxAmount(new BigDecimal("10.00"));
        multiSecond.setCostPriceAtSale(new BigDecimal("20.000000"));
        multi.getItems().add(multiSecond);
        em.persist(multi);
        multiFirst.setQuantityRefunded(2);
        multiSecond.setQuantityRefunded(1);
        SaleReturn multiReturn = new SaleReturn();
        multiReturn.setSale(multi);
        multiReturn.setReturnedBy(user);
        multiReturn.setReturnDate(LocalDateTime.of(2026, 11, 13, 10, 0));
        multiReturn.setReason("Wrong size on both lines");
        multiReturn.setRefundMethod(PaymentMethod.CASH);
        multiReturn.setTotalReturnAmount(new BigDecimal("275.00"));
        multiReturn.getItems().add(returnLine(multiReturn, multiFirst, 2, "220.00"));
        multiReturn.getItems().add(returnLine(multiReturn, multiSecond, 1, "55.00"));
        em.persist(multiReturn);

        // 12. Card refund: the money went back over the card network, so none of it
        // may leave the cash account.
        Sale cardSale = sale("INV-CARDTENDER", Sale.SaleType.CASH, false,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("110.00"),
                LocalDateTime.of(2026, 11, 14, 10, 0));
        em.persist(cardSale);
        wholeReturn(cardSale, PaymentMethod.CARD, "110.00",
                LocalDateTime.of(2026, 11, 15, 10, 0), "Card refund");

        // 13. Store-credit refund: owed to the customer as credit, so it sits in the
        // refund liability, not in cash or tender clearing.
        Sale creditSale = sale("INV-STORECREDIT", Sale.SaleType.CASH, false,
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("50.00"),
                LocalDateTime.of(2026, 11, 16, 10, 0));
        SaleItem creditLine = creditSale.getItems().get(0);
        creditLine.setProduct(secondProduct);
        creditLine.setUnitPrice(new BigDecimal("50.00"));
        creditLine.setTotalPrice(new BigDecimal("50.00"));
        creditLine.setCostPriceAtSale(new BigDecimal("20.000000"));
        em.persist(creditSale);
        wholeReturn(creditSale, PaymentMethod.STORE_CREDIT, "50.00",
                LocalDateTime.of(2026, 11, 17, 10, 0), "Store credit refund");

        // 14. The till itself: every cash event of the month recorded the way the
        // drawer services record it, so the cash account has something physical to
        // be checked against. Card and store-credit refunds deliberately have no
        // movement here — no cash moved.
        CashDrawer drawer = new CashDrawer();
        drawer.setName("Integration drawer");
        drawer.setCode("GL-INT-DRW");
        em.persist(drawer);
        drawerSession = new CashDrawerSession();
        drawerSession.setDrawer(drawer);
        drawerSession.setOpenedBy(user);
        drawerSession.setOpeningFloat(new BigDecimal("500.00"));
        drawerSession.setOpenedAt(LocalDateTime.of(2026, 11, 1, 8, 0));
        em.persist(drawerSession);

        drawerMovement(DrawerMovementType.OPENING_FLOAT, "500.00",
                LocalDateTime.of(2026, 11, 1, 8, 0), "DRAWER_SESSION", drawerSession.getId());
        drawerMovement(DrawerMovementType.CASH_SALE, "176.00",
                LocalDateTime.of(2026, 11, 5, 10, 0), "SALE", promoted.getId());
        drawerMovement(DrawerMovementType.CASH_REFUND, "-88.00",
                LocalDateTime.of(2026, 11, 10, 12, 0), "SALE_RETURN", ret.getId());
        drawerMovement(DrawerMovementType.CASH_SALE, "110.00",
                LocalDateTime.of(2026, 11, 6, 9, 0), "SALE", voided.getId());
        drawerMovement(DrawerMovementType.CASH_REFUND, "-110.00",
                LocalDateTime.of(2026, 11, 12, 9, 0), "SALE", voided.getId());
        drawerMovement(DrawerMovementType.CASH_SALE, "550.00",
                LocalDateTime.of(2026, 11, 12, 10, 0), "SALE", multi.getId());
        drawerMovement(DrawerMovementType.CASH_REFUND, "-275.00",
                LocalDateTime.of(2026, 11, 13, 10, 0), "SALE_RETURN", multiReturn.getId());
        drawerMovement(DrawerMovementType.CASH_SALE, "110.00",
                LocalDateTime.of(2026, 11, 14, 10, 0), "SALE", cardSale.getId());
        drawerMovement(DrawerMovementType.CASH_SALE, "50.00",
                LocalDateTime.of(2026, 11, 16, 10, 0), "SALE", creditSale.getId());
        drawerMovement(DrawerMovementType.PAY_IN, "110.00",
                LocalDateTime.of(2026, 11, 22, 9, 0), "AR_PAYMENT", arPayment.getId());

        em.flush();
        em.clear();

        List<JournalEntryResponse> entries = glService.journal(START, END);

        // I4: debits equal credits across the whole mixed month.
        assertEquals(0, sumDebits(entries).compareTo(sumCredits(entries)),
                "journal must balance across all event classes");

        // Final check (spec 5): the cash account equals the money the till should
        // hold, taken from the drawer ledger itself rather than recomputed here.
        BigDecimal tillExpected = cashDrawerMovementRepository.sumAmountBySession(drawerSession.getId());
        assertEquals(0, netDebit(entries, "CASH").compareTo(tillExpected),
                "GL cash account must equal the drawer's expected cash: GL "
                        + netDebit(entries, "CASH") + " vs till " + tillExpected);

        // A card refund and a store-credit refund never touch the till, so their
        // money has to sit in the accounts that describe it.
        assertEquals(0, netDebit(entries, "TENDER_CLEARING").compareTo(new BigDecimal("-110.00")),
                "the card refund settles through tender clearing, not cash");
        assertEquals(0, netDebit(entries, "REFUND_LIABILITY").compareTo(new BigDecimal("-50.00")),
                "the store-credit refund is owed as credit, not paid out");

        // I4: GL COGS equals report COGS (net of the partial and multi-item
        // returns; the voided sale cancels out).
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

    private SaleReturnItem returnLine(SaleReturn saleReturn, SaleItem saleItem,
                                      int quantity, String amount) {
        SaleReturnItem item = new SaleReturnItem();
        item.setSaleReturn(saleReturn);
        item.setSaleItem(saleItem);
        item.setQuantityReturned(quantity);
        item.setReturnAmount(new BigDecimal(amount));
        return item;
    }

    /** A refund of the whole invoice, paid out through the given tender. */
    private SaleReturn wholeReturn(Sale sale, PaymentMethod refundMethod, String gross,
                                   LocalDateTime date, String reason) {
        SaleItem line = sale.getItems().get(0);
        line.setQuantityRefunded(line.getQuantity());
        SaleReturn saleReturn = new SaleReturn();
        saleReturn.setSale(sale);
        saleReturn.setReturnedBy(user);
        saleReturn.setReturnDate(date);
        saleReturn.setReason(reason);
        saleReturn.setRefundMethod(refundMethod);
        saleReturn.setTotalReturnAmount(new BigDecimal(gross));
        saleReturn.getItems().add(returnLine(saleReturn, line, line.getQuantity(), gross));
        return em.persist(saleReturn);
    }

    private CashDrawerMovement drawerMovement(DrawerMovementType type, String amount,
                                              LocalDateTime occurredAt, String referenceType,
                                              Long referenceId) {
        CashDrawerMovement movement = new CashDrawerMovement();
        movement.setDrawerSession(drawerSession);
        movement.setMovementType(type);
        movement.setAmount(new BigDecimal(amount));
        movement.setOccurredAt(occurredAt);
        movement.setReferenceType(referenceType);
        movement.setReferenceId(referenceId);
        movement.setPerformedBy(user);
        return em.persist(movement);
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