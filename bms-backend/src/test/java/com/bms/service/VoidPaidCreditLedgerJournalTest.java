package com.bms.service;

import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.ArPayment;
import com.bms.entity.PaymentMethod;
import com.bms.entity.PeriodClose;
import com.bms.entity.Product;
import com.bms.entity.ProfitLedger;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Audit item 2: a voided credit invoice that was already collected through AR
 * must reverse those collections in the ledger GL path. Without the ARP-REV
 * rows the invoice leaves AR negative and the returned cash unmatched.
 */
@DataJpaTest
@ContextConfiguration(classes = VoidPaidCreditLedgerJournalTest.JpaConfig.class)
@Import({GlService.class, LedgerPostingService.class, LedgerHistoryService.class, PeriodGuard.class,
        LedgerReadService.class, InventoryLossService.class, ReportService.class, ShopTimeService.class})
class VoidPaidCreditLedgerJournalTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private GlService glService;
    @Autowired private LedgerPostingService postingService;
    @Autowired private ProfitLedgerRepository profitLedgerRepository;
    @Autowired private PeriodCloseRepository periodCloseRepository;

    @MockBean private AuditLogService auditLogService;
    @MockBean private CashShiftService cashShiftService;
    @MockBean private CashDrawerSessionService cashDrawerSessionService;
    @MockBean private SystemSettingService systemSettingService;

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 30);
    private static final BigDecimal COST = new BigDecimal("40.000000");

    private User cashier;

    @BeforeEach
    void seedShop() {
        glService.ensureDefaults();
        em.persist(setting(LedgerReadService.CUTOVER_KEY, "2026-11-01T00:00:00"));
        em.persist(setting(PeriodGuard.LOCK_KEY, "0"));

        Role role = new Role();
        role.setName(Role.RoleName.ROLE_CASHIER);
        em.persist(role);
        cashier = new User();
        cashier.setUsername("void-credit-cashier");
        cashier.setEmail("void-credit@example.test");
        cashier.setPassword("unused-fixture-password");
        cashier.setFirstName("Void");
        cashier.setLastName("Cashier");
        cashier.setRole(role);
        em.persist(cashier);
        em.flush();
    }

    @Test
    void voidOfAFullyCollectedCreditInvoiceLeavesNoArOrCashBalance() {
        Sale sale = creditSale();
        postingService.postSaleProfit(sale, sale.getItems().get(0));

        arPayment(sale, "60.00", PaymentMethod.CASH);
        arPayment(sale, "40.00", PaymentMethod.CARD);

        sale.setIsVoided(true);
        sale.setVoidedAt(LocalDateTime.of(2026, 11, 12, 10, 0));
        sale.setAmountPaid(new BigDecimal("100.00"));
        sale.setPaymentStatus(Sale.PaymentStatus.PAID);
        em.persistAndFlush(sale);
        postingService.postVoidProfit(sale, sale.getItems().get(0), 1,
                LocalDateTime.of(2026, 11, 12, 10, 0));

        List<JournalEntryResponse> entries = glService.journal(START, END);
        assertFalse(entries.isEmpty(), "journal must contain ledger rows");

        assertEquals(0, netDebit(entries, "ACCOUNTS_RECEIVABLE").compareTo(BigDecimal.ZERO),
                "AR must be settled after the void and the collection reversals: " + entries);
        assertEquals(0, netDebit(entries, "CASH").compareTo(BigDecimal.ZERO),
                "cash collected on the invoice must have gone back out: " + entries);
        assertEquals(0, netCredit(entries, "REFUND_LIABILITY").compareTo(new BigDecimal("40.00")),
                "the reversed card collection becomes store credit: " + entries);
        assertEquals(0, netDebits(entries).compareTo(netCredits(entries)), "journal must balance");
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

    private Sale creditSale() {
        Product product = new Product();
        product.setSku("VOID-CREDIT-1");
        product.setName("Void credit fixture");
        product.setUnitPrice(new BigDecimal("100.00"));
        product.setCostPrice(COST);
        product.setStockQuantity(1);
        em.persistAndFlush(product);

        Sale sale = new Sale();
        sale.setInvoiceNumber("INV-VOID-CREDIT-1");
        sale.setCustomerDisplayName("Credit Customer");
        sale.setCashierId(cashier.getId());
        sale.setSaleDate(LocalDateTime.of(2026, 11, 5, 10, 0));
        sale.setSubtotal(new BigDecimal("100.00"));
        sale.setTaxAmount(BigDecimal.ZERO);
        sale.setTotalAmount(new BigDecimal("100.00"));
        sale.setAmountPaid(BigDecimal.ZERO);
        sale.setSaleType(Sale.SaleType.CREDIT);
        sale.setPaymentMethod(Sale.PaymentMethod.CREDIT);
        sale.setPaymentStatus(Sale.PaymentStatus.PARTIAL);
        sale.setIsVoided(false);

        SaleItem item = new SaleItem();
        item.setSale(sale);
        item.setProduct(product);
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setTotalPrice(new BigDecimal("100.00"));
        item.setTaxAmount(BigDecimal.ZERO);
        item.setCostPriceAtSale(COST);
        item.setQuantityRefunded(0);
        sale.getItems().add(item);
        return em.persistAndFlush(sale);
    }

    private void arPayment(Sale sale, String amount, PaymentMethod method) {
        ArPayment payment = new ArPayment();
        payment.setInvoice(sale);
        payment.setAmount(new BigDecimal(amount));
        payment.setPaymentMethod(method);
        payment.setPaymentDate(LocalDateTime.of(2026, 11, 6, 9, 0));
        payment.setReversedAt(LocalDateTime.of(2026, 11, 12, 10, 0));
        payment.setReversalNote("Invoice voided");
        payment.setRecordedBy(cashier);
        em.persistAndFlush(payment);
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private static BigDecimal netDebit(List<JournalEntryResponse> entries, String account) {
        return entries.stream().filter(e -> account.equals(e.getEventType()))
                .map(e -> e.getDebit().subtract(e.getCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal netCredit(List<JournalEntryResponse> entries, String account) {
        return netDebit(entries, account).negate();
    }

    private static BigDecimal netDebits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal netCredits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
