package com.bms.service;

import com.bms.entity.PaymentMethod;
import com.bms.entity.PeriodClose;
import com.bms.entity.Product;
import com.bms.entity.ProfitLedger;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SalePayment;
import com.bms.entity.SaleReturn;
import com.bms.entity.SaleReturnItem;
import com.bms.entity.StockMovement;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.ProfitLedgerRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.service.LedgerReadService;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O4 acceptance: deleting old sales must survive real foreign-key checks, keep
 * the accounting history it points at, leave unrelated movements alone, and
 * refuse closed periods, unpaid invoices and an unconfirmed backup.
 */
@DataJpaTest
@ContextConfiguration(classes = SaleBulkDeleteTest.JpaConfig.class)
@Import({DataRetentionService.class, ShopTimeService.class, LedgerReadService.class})
class SaleBulkDeleteTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private DataRetentionService dataRetentionService;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private ProfitLedgerRepository profitLedgerRepository;
    @Autowired private PeriodCloseRepository periodCloseRepository;

    @MockBean private AuditLogService auditLogService;

    private User cashier;

    @BeforeEach
    void seedUser() {
        Role role = new Role();
        role.setName(Role.RoleName.ROLE_CASHIER);
        em.persist(role);

        cashier = new User();
        cashier.setUsername("old-sale-cashier");
        cashier.setEmail("old-sale-cashier@example.test");
        cashier.setPassword("unused-fixture-password");
        cashier.setFirstName("Old");
        cashier.setLastName("Cashier");
        cashier.setRole(role);
        em.persist(cashier);
        em.flush();
    }

    @Test
    void deletesReturnsPaymentsAndMovementsInForeignKeyOrder() {
        Sale sale = sale("OLD-1", LocalDateTime.of(2023, 4, 5, 10, 0), Sale.PaymentStatus.PAID);
        SaleReturn saleReturn = returnOf(sale);
        payment(sale, new BigDecimal("59.00"));
        movement(sale, StockMovement.ReferenceType.SALE, sale.getId(), sale.getId(), null);
        movement(sale, StockMovement.ReferenceType.RETURN, sale.getId(), sale.getId(), saleReturn.getId());
        // An adjustment that happens to carry the same numeric id as the sale: it
        // belongs to nothing in this delete and must survive.
        StockMovement unrelated = movement(sale, StockMovement.ReferenceType.STOCK_ADJUSTMENT,
                sale.getId(), null, null);
        ledgerRow(sale);
        em.flush();

        Map<String, Object> result = dataRetentionService.deleteSalesOlderThanYears(1, true, cashier.getId());

        assertEquals(1, result.get("deletedSales"));
        assertEquals(1, result.get("deletedReturns"));
        assertEquals(0L, count("SELECT COUNT(s) FROM Sale s WHERE s.invoiceNumber = 'OLD-1'"));
        assertEquals(0L, count("SELECT COUNT(r) FROM SaleReturn r"));
        assertEquals(0L, count("SELECT COUNT(p) FROM SalePayment p"));
        assertEquals(0L, count("SELECT COUNT(i) FROM SaleItem i"));
        assertEquals(0L, count("""
                SELECT COUNT(m) FROM StockMovement m
                WHERE m.referenceType IN (:types)
                """, Map.of("types", List.of(StockMovement.ReferenceType.SALE,
                        StockMovement.ReferenceType.RETURN))));
        assertTrue(stockMovementRepository.findById(unrelated.getId()).isPresent(),
                "an unrelated movement must never be swept up by the sale id");

        List<ProfitLedger> ledgers = profitLedgerRepository.findAll();
        assertEquals(1, ledgers.size(), "ledger history is append-only");
        assertEquals(sale.getId(), ledgers.get(0).getSourceId());
    }

    @Test
    void refusesToTouchASaleInAClosedPeriod() {
        Sale sale = sale("OLD-2", LocalDateTime.of(2023, 4, 5, 10, 0), Sale.PaymentStatus.PAID);
        movement(sale, StockMovement.ReferenceType.SALE, sale.getId(), sale.getId(), null);
        em.persist(periodClose(YearMonth.from(sale.getSaleDate())));
        em.flush();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dataRetentionService.deleteSalesOlderThanYears(1, true, cashier.getId()));

        assertTrue(ex.getMessage().contains("closed accounting periods"), ex.getMessage());
        assertNotNull(em.find(Sale.class, sale.getId()));
        assertEquals(1L, stockMovementRepository.count());
    }

    @Test
    void refusesToTouchASaleWithAnOutstandingBalance() {
        Sale sale = sale("OLD-3", LocalDateTime.of(2023, 4, 5, 10, 0), Sale.PaymentStatus.PARTIAL);
        em.flush();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dataRetentionService.deleteSalesOlderThanYears(1, true, cashier.getId()));

        assertTrue(ex.getMessage().contains("outstanding balance"), ex.getMessage());
        assertNotNull(em.find(Sale.class, sale.getId()));
    }

    @Test
    void requiresABackupConfirmationBeforeAnythingIsDeleted() {
        Sale sale = sale("OLD-4", LocalDateTime.of(2023, 4, 5, 10, 0), Sale.PaymentStatus.PAID);
        em.flush();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dataRetentionService.deleteSalesOlderThanYears(1, false, cashier.getId()));

        assertTrue(ex.getMessage().contains("backup"), ex.getMessage());
        assertNotNull(em.find(Sale.class, sale.getId()));
    }

    @Test
    void recentSalesAreOutsideTheCutoffAndStay() {
        sale("NEW-1", LocalDateTime.of(2026, 4, 5, 10, 0), Sale.PaymentStatus.PAID);
        em.flush();

        Map<String, Object> result = dataRetentionService.deleteSalesOlderThanYears(1, true, cashier.getId());

        assertEquals(0, result.get("deletedSales"));
        assertEquals(1L, count("SELECT COUNT(s) FROM Sale s WHERE s.invoiceNumber = 'NEW-1'"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Sale sale(String invoice, LocalDateTime when, Sale.PaymentStatus status) {
        Product product = new Product();
        product.setSku(invoice + "-SKU");
        product.setName("Retention fixture " + invoice);
        product.setUnitPrice(new BigDecimal("59.00"));
        product.setCostPrice(new BigDecimal("30.00"));
        product.setStockQuantity(10);
        em.persist(product);

        Sale sale = new Sale();
        sale.setInvoiceNumber(invoice);
        sale.setCustomerDisplayName("Walk-in");
        sale.setCashierId(cashier.getId());
        sale.setSaleDate(when);
        sale.setSubtotal(new BigDecimal("59.00"));
        sale.setTaxAmount(BigDecimal.ZERO);
        sale.setTotalAmount(new BigDecimal("59.00"));
        sale.setAmountPaid(status == Sale.PaymentStatus.PAID ? new BigDecimal("59.00") : BigDecimal.ZERO);
        sale.setSaleType(Sale.SaleType.CASH);
        sale.setPaymentMethod(Sale.PaymentMethod.CASH);
        sale.setPaymentStatus(status);
        sale.setIsVoided(false);

        SaleItem item = new SaleItem();
        item.setSale(sale);
        item.setProduct(product);
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("59.00"));
        item.setTotalPrice(new BigDecimal("59.00"));
        item.setTaxAmount(BigDecimal.ZERO);
        item.setCostPriceAtSale(new BigDecimal("30.00"));
        item.setQuantityRefunded(0);
        sale.getItems().add(item);
        return em.persistAndFlush(sale);
    }

    private SaleReturn returnOf(Sale sale) {
        SaleReturn saleReturn = new SaleReturn();
        saleReturn.setSale(sale);
        saleReturn.setReturnedBy(cashier);
        saleReturn.setReturnDate(sale.getSaleDate().plusDays(1));
        saleReturn.setReason("Fixture return");
        saleReturn.setRefundMethod(PaymentMethod.CASH);
        saleReturn.setTotalReturnAmount(new BigDecimal("59.00"));
        saleReturn.setDebtReductionAmount(BigDecimal.ZERO);
        saleReturn.setRefundAmount(new BigDecimal("59.00"));
        for (SaleItem item : sale.getItems()) {
            SaleReturnItem returnItem = new SaleReturnItem();
            returnItem.setSaleReturn(saleReturn);
            returnItem.setSaleItem(item);
            returnItem.setQuantityReturned(item.getQuantity());
            returnItem.setReturnAmount(item.getTotalPrice());
            saleReturn.getItems().add(returnItem);
        }
        return em.persistAndFlush(saleReturn);
    }

    private SalePayment payment(Sale sale, BigDecimal amount) {
        SalePayment payment = new SalePayment();
        payment.setSale(sale);
        payment.setPaymentMethod(PaymentMethod.CASH);
        payment.setAmount(amount);
        payment.setPaidAt(sale.getSaleDate());
        return em.persistAndFlush(payment);
    }

    private StockMovement movement(Sale sale, StockMovement.ReferenceType type, Long referenceId,
                                   Long sourceSaleId, Long sourceReturnId) {
        StockMovement movement = new StockMovement();
        movement.setProduct(sale.getItems().get(0).getProduct());
        movement.setMovementType(type == StockMovement.ReferenceType.SALE
                ? StockMovement.MovementType.OUT : StockMovement.MovementType.ADJUSTMENT_IN);
        movement.setQuantity(1);
        movement.setReferenceType(type);
        movement.setReferenceId(referenceId);
        movement.setSourceSaleId(sourceSaleId);
        movement.setSourceReturnId(sourceReturnId);
        movement.setCreatedBy(cashier);
        movement.setMovementDate(sale.getSaleDate());
        return em.persistAndFlush(movement);
    }

    private ProfitLedger ledgerRow(Sale sale) {
        ProfitLedger row = new ProfitLedger();
        row.setPeriod(YearMonth.from(sale.getSaleDate()).toString());
        row.setEventType(ProfitLedger.EventType.SALE);
        row.setSourceType("SALE");
        row.setSourceId(sale.getId());
        row.setProductId(sale.getItems().get(0).getProduct().getId());
        row.setNetRevenue(new BigDecimal("59.0000"));
        row.setCogs(new BigDecimal("30.0000"));
        row.setOccurredAt(sale.getSaleDate());
        return em.persistAndFlush(row);
    }

    private PeriodClose periodClose(YearMonth month) {
        PeriodClose periodClose = new PeriodClose();
        periodClose.setPeriod(month.toString());
        periodClose.setClosedAt(LocalDateTime.of(2026, 11, 1, 9, 0));
        periodClose.setClosedBy(cashier.getId());
        return periodClose;
    }

    private long count(String jpql) {
        return count(jpql, Map.of());
    }

    private long count(String jpql, Map<String, Object> parameters) {
        var query = em.getEntityManager().createQuery(jpql, Long.class);
        parameters.forEach(query::setParameter);
        return query.getSingleResult();
    }
}
