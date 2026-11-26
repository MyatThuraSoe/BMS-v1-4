package com.bms.service;

import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.InventoryLedger;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.PurchasePayment;
import com.bms.entity.Role;
import com.bms.entity.SystemSetting;
import com.bms.entity.User;
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
 * Audit item 3: recoverable purchase tax must be journaled in the ledger path.
 * Inventory excludes it, but the supplier payable includes it, so omitting the
 * Input Tax line leaves AP negative once the invoice is paid.
 */
@DataJpaTest
@ContextConfiguration(classes = RecoverableInputTaxLedgerJournalTest.JpaConfig.class)
@Import({GlService.class, LedgerPostingService.class, LedgerHistoryService.class, PeriodGuard.class,
        LedgerReadService.class, InventoryLossService.class, ReportService.class, ShopTimeService.class})
class RecoverableInputTaxLedgerJournalTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private GlService glService;

    @MockBean private AuditLogService auditLogService;
    @MockBean private CashShiftService cashShiftService;
    @MockBean private CashDrawerSessionService cashDrawerSessionService;
    @MockBean private SystemSettingService systemSettingService;

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 30);
    private static final BigDecimal COST = new BigDecimal("100.000000");

    private User recorder;

    @BeforeEach
    void seedShop() {
        glService.ensureDefaults();
        em.persist(setting(LedgerReadService.CUTOVER_KEY, "2026-11-01T00:00:00"));
        em.persist(setting(PeriodGuard.LOCK_KEY, "0"));

        Role role = new Role();
        role.setName(Role.RoleName.ROLE_ADMIN);
        em.persist(role);
        recorder = new User();
        recorder.setUsername("tax-fixture");
        recorder.setEmail("tax-fixture@example.test");
        recorder.setPassword("unused-fixture-password");
        recorder.setFirstName("Tax");
        recorder.setLastName("Fixture");
        recorder.setRole(role);
        em.persist(recorder);
        em.flush();
    }

    @Test
    void recoverableTaxIsPostedAndTheSupplierPayableIsSettled() {
        Purchase purchase = recoverablePurchase();
        PurchaseItem item = purchase.getItems().get(0);
        receiptLedgerRow(purchase, item);
        supplierPayment(purchase);

        List<JournalEntryResponse> entries = glService.journal(START, END);
        assertFalse(entries.isEmpty());

        assertEquals(0, netDebit(entries, "INPUT_TAX").compareTo(new BigDecimal("10.00")),
                "recoverable tax must be an asset: " + entries);
        assertEquals(0, netDebit(entries, "ACCOUNTS_RECEIVABLE").compareTo(BigDecimal.ZERO));
        assertEquals(0, netDebit(entries, "ACCOUNTS_PAYABLE").compareTo(BigDecimal.ZERO),
                "paying 110.00 must clear the goods 100.00 plus tax 10.00: " + entries);
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

    private Purchase recoverablePurchase() {
        Product product = new Product();
        product.setSku("TAX-1");
        product.setName("Recoverable tax fixture");
        product.setUnitPrice(new BigDecimal("150.00"));
        product.setCostPrice(COST);
        product.setStockQuantity(1);
        em.persistAndFlush(product);

        Purchase purchase = new Purchase();
        purchase.setPurchaseNumber("PO-TAX-1");
        purchase.setPurchaseDate(LocalDate.of(2026, 11, 5));
        purchase.setSubtotal(new BigDecimal("100.00"));
        purchase.setTaxAmount(new BigDecimal("10.00"));
        purchase.setTotalAmount(new BigDecimal("110.00"));
        purchase.setDiscountAmount(BigDecimal.ZERO);
        purchase.setTaxRecoverable(true);
        purchase.setReceivingStatus(Purchase.ReceivingStatus.RECEIVED);
        purchase.setReceivedAt(LocalDateTime.of(2026, 11, 6, 10, 0));
        purchase.setExchangeRate(BigDecimal.ONE);
        purchase.setCreatedBy(recorder.getId());
        purchase.setIsActive(true);

        PurchaseItem item = new PurchaseItem();
        item.setPurchase(purchase);
        item.setProduct(product);
        item.setQuantity(1);
        item.setReceivedQuantity(1);
        item.setUnitCost(new BigDecimal("100.00"));
        item.setTotalCost(new BigDecimal("100.00"));
        item.setLandedUnitCost(new BigDecimal("100.00"));
        item.setBaseUnitCost(new BigDecimal("100.00"));
        item.setExchangeRate(BigDecimal.ONE);
        purchase.getItems().add(item);
        return em.persistAndFlush(purchase);
    }

    private void receiptLedgerRow(Purchase purchase, PurchaseItem item) {
        InventoryLedger row = new InventoryLedger();
        row.setEventType(InventoryLedger.EventType.RECEIPT);
        row.setSourceType("PURCHASE");
        row.setSourceId(purchase.getId());
        row.setLineKey(item.getId() + ":0");
        row.setProductId(item.getProduct().getId());
        row.setQtyDelta(1);
        row.setQtyAfter(1);
        row.setUnitCost(COST);
        row.setUnitCostAfter(COST);
        row.setValueDelta(COST);
        row.setValueAfter(COST);
        row.setReason("Purchase receipt");
        row.setOccurredAt(LocalDateTime.of(2026, 11, 6, 10, 0));
        row.setPostedAt(LocalDateTime.of(2026, 11, 6, 10, 0));
        em.persistAndFlush(row);
    }

    private void supplierPayment(Purchase purchase) {
        PurchasePayment payment = new PurchasePayment();
        payment.setPurchase(purchase);
        payment.setAmount(new BigDecimal("110.00"));
        payment.setPaymentMethod(PaymentMethod.CASH);
        payment.setPaymentDate(LocalDateTime.of(2026, 11, 7, 10, 0));
        payment.setRecordedBy(recorder);
        em.persistAndFlush(payment);
    }

    private static BigDecimal netDebit(List<JournalEntryResponse> entries, String account) {
        return entries.stream().filter(e -> account.equals(e.getEventType()))
                .map(e -> e.getDebit().subtract(e.getCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal netDebits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal netCredits(List<JournalEntryResponse> entries) {
        return entries.stream().map(JournalEntryResponse::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
