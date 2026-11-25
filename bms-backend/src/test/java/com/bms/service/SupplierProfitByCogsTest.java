package com.bms.service;

import com.bms.dto.response.SupplierProfitDto;
import com.bms.entity.InventoryLedger;
import com.bms.entity.Product;
import com.bms.entity.ProfitLedger;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.Supplier;
import com.bms.entity.SystemSetting;
import com.bms.repository.SystemSettingRepository;
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
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.lenient;

/**
 * Audit item 11: supplier profitability must use the same ledger COGS as the
 * P&amp;L, not the invoice-currency unit cost on the purchase line. The receipt
 * was valued at 60.000000 base units, while the invoice says 100.00; the sale
 * ledger records revenue 200.00 and COGS 120.00.
 * Runs on an isolated in-memory H2 slice (no production data, no web context).
 */
@DataJpaTest
@ContextConfiguration(classes = SupplierProfitByCogsTest.JpaConfig.class)
@Import({ReportService.class, InventoryLossService.class, LedgerReadService.class, ShopTimeService.class})
class SupplierProfitByCogsTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private ReportService reportService;
    @Autowired private ShopTimeService shopTimeService;

    // Same collaborators as ReportProfitAggregationIntegrationTest.
    @MockBean private CashShiftService cashShiftService;
    @MockBean private CashDrawerSessionService cashDrawerSessionService;
    @MockBean private SystemSettingService systemSettingService;
    @MockBean private SystemSettingRepository systemSettingRepository;

    @Test
    void supplierProfitUsesLedgerCogsInsteadOfInvoiceUnitCost() {
        LocalDate today = shopTimeService.today();
        LocalDate startDate = today.minusDays(5);
        LocalDate endDate = today;
        LocalDate purchaseDate = today.minusDays(4);
        LocalDate saleDate = today.minusDays(2);
        BigDecimal baseUnitCost = new BigDecimal("60.000000");
        BigDecimal invoiceUnitCost = new BigDecimal("100.000000");
        BigDecimal fxRate = new BigDecimal("0.600000");

        // Ledger reads are active; mirror the persisted value through the mocked
        // repository so LedgerReadService.isActive() sees it in this slice.
        SystemSetting cutover = new SystemSetting();
        cutover.setSettingKey(LedgerReadService.CUTOVER_KEY);
        cutover.setSettingValue(purchaseDate.atStartOfDay().toString());
        em.persist(cutover);
        em.flush();
        lenient().when(systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY))
                .thenReturn(Optional.of(cutover));

        Supplier supplier = new Supplier();
        supplier.setName("Ledger COGS supplier");
        supplier.setPhone("A11-555-0101");
        supplier.setIsActive(true);
        em.persist(supplier);
        em.flush();

        Product product = new Product();
        product.setSku("A11-COGS");
        product.setName("Supplier COGS fixture");
        product.setUnitPrice(new BigDecimal("100.00"));
        product.setCostPrice(baseUnitCost);
        product.setStockQuantity(0);
        em.persist(product);
        em.flush();
        Long productId = product.getId();

        Purchase purchase = new Purchase();
        purchase.setPurchaseNumber("PO-A11-COGS");
        purchase.setSupplier(supplier);
        purchase.setPurchaseDate(purchaseDate);
        purchase.setSubtotal(new BigDecimal("200.00"));
        purchase.setTaxAmount(BigDecimal.ZERO);
        purchase.setTotalAmount(new BigDecimal("200.00"));
        purchase.setCurrencyCode("FC");
        purchase.setExchangeRate(fxRate);
        purchase.setPaymentStatus(Purchase.PaymentStatus.PENDING);
        purchase.setReceivingStatus(Purchase.ReceivingStatus.RECEIVED);
        purchase.setCreatedBy(1L);
        purchase.setIsActive(true);
        em.persist(purchase);
        em.flush();

        PurchaseItem purchaseItem = new PurchaseItem();
        purchaseItem.setPurchase(purchase);
        purchaseItem.setProduct(product);
        purchaseItem.setQuantity(2);
        purchaseItem.setUnitCost(invoiceUnitCost);
        purchaseItem.setTotalCost(new BigDecimal("200.00"));
        purchaseItem.setReceivedQuantity(2);
        purchaseItem.setReturnedQuantity(0);
        purchaseItem.setExchangeRate(fxRate);
        purchaseItem.setLandedUnitCost(baseUnitCost);
        purchaseItem.setBaseUnitCost(baseUnitCost);
        em.persist(purchaseItem);
        em.flush();

        // Authoritative landed cost: 2 units valued at 60.000000 each.
        InventoryLedger receipt = new InventoryLedger();
        receipt.setProductId(productId);
        receipt.setEventType(InventoryLedger.EventType.RECEIPT);
        receipt.setSourceType("PURCHASE");
        receipt.setSourceId(purchase.getId());
        receipt.setLineKey("receipt-1");
        receipt.setQtyDelta(2);
        receipt.setUnitCost(baseUnitCost);
        receipt.setValueDelta(new BigDecimal("120.000000"));
        receipt.setQtyAfter(2);
        receipt.setValueAfter(new BigDecimal("120.000000"));
        receipt.setUnitCostAfter(baseUnitCost);
        receipt.setOccurredAt(purchaseDate.atTime(10, 0));
        receipt.setPostedAt(purchaseDate.atTime(10, 5));
        em.persist(receipt);
        em.flush();

        ProfitLedger sale = new ProfitLedger();
        sale.setPeriod(YearMonth.from(saleDate).toString());
        sale.setEventType(ProfitLedger.EventType.SALE);
        sale.setSourceType("SALE");
        sale.setSourceId(9201L);
        sale.setProductId(productId);
        sale.setLineKey("line-1");
        sale.setQuantity(2);
        sale.setNetRevenue(new BigDecimal("200.0000"));
        sale.setCogs(new BigDecimal("120.0000"));
        sale.setExpense(BigDecimal.ZERO);
        sale.setTaxAmount(BigDecimal.ZERO);
        sale.setOccurredAt(saleDate.atTime(11, 0));
        sale.setPostedAt(saleDate.atTime(11, 5));
        em.persist(sale);
        em.flush();

        List<SupplierProfitDto> rows = reportService.getProfitBySupplier(startDate, endDate);
        SupplierProfitDto dto = rows.stream()
                .filter(row -> supplier.getId().equals(row.getSupplierId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("supplier row missing from profit-by-supplier report"));

        assertMoney(new BigDecimal("120.00"), dto.getTotalSuppliedCost(),
                "supplied cost must be ledger COGS, not purchase-item unit_cost times received quantity");
        assertMoney(new BigDecimal("200.00"), dto.getEstimatedRevenue(),
                "estimated revenue must come from the sale profit ledger row in the report window");
        assertMoney(new BigDecimal("80.00"), dto.getEstimatedProfit(),
                "estimated profit must be ledger revenue minus ledger COGS");
    }

    private static void assertMoney(BigDecimal expected, BigDecimal actual, String message) {
        assertNotNull(actual, message + " (was null)");
        assertEquals(0, expected.compareTo(actual),
                message + " (expected " + expected.toPlainString() + " but was " + actual.toPlainString() + ")");
    }
}
