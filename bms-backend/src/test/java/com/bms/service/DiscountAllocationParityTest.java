package com.bms.service;

import com.bms.entity.Product;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

@DataJpaTest
@ContextConfiguration(classes = DiscountAllocationParityTest.JpaConfig.class)
@Import({ReportService.class, InventoryLossService.class, LedgerReadService.class, ShopTimeService.class})
class DiscountAllocationParityTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private ReportService reportService;
    @Autowired private ShopTimeService shopTimeService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private CashShiftService cashShiftService;
    @MockBean private CashDrawerSessionService cashDrawerSessionService;
    @MockBean private SystemSettingService systemSettingService;
    @MockBean private SystemSettingRepository systemSettingRepository;

    @Test
    void sqlAllocationMatchesJavaAllocatorForMixedShapes() {
        Product product = new Product();
        product.setSku("TEMP-DISC-PARITY");
        product.setName("Parity product");
        product.setUnitPrice(BigDecimal.ONE);
        product.setCostPrice(BigDecimal.ZERO);
        product.setStockQuantity(0);
        em.persist(product);
        em.flush();
        Long productId = product.getId();

        SystemSetting legacy = new SystemSetting();
        legacy.setSettingKey(LedgerReadService.LEGACY_ENABLED_KEY);
        legacy.setSettingValue("true");
        lenient().when(systemSettingRepository.findBySettingKey(LedgerReadService.LEGACY_ENABLED_KEY))
                .thenReturn(Optional.of(legacy));
        lenient().when(systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY))
                .thenReturn(Optional.empty());
        lenient().when(systemSettingRepository.findBySettingKey(ShopTimeService.TIMEZONE_KEY))
                .thenReturn(Optional.empty());

        LocalDate today = shopTimeService.today();
        Timestamp at = Timestamp.valueOf(today.atTime(9, 0));

        String saleSql = """
            INSERT INTO sales (id, invoice_number, customer_display_name, cashier_id, sale_date,
                subtotal, tax_amount, total_amount, amount_paid, amount_returned, change_given,
                payment_method, sale_type, payment_status, return_status, is_voided, is_active,
                discount_amount, promotion_discount, price_list_discount, version, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        String itemSql = """
            INSERT INTO sale_items (id, sale_id, product_id, quantity, unit_price, total_price,
                tax_amount, cost_price_at_sale, quantity_refunded, line_discount)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """;

        BigDecimal[] grossChoices = {
                new BigDecimal("0.01"), new BigDecimal("0.05"), new BigDecimal("0.10"),
                new BigDecimal("0.33"), new BigDecimal("1.00"), new BigDecimal("3.00"),
                new BigDecimal("7.00")
        };
        BigDecimal[] discountChoices = {
                new BigDecimal("0.01"), new BigDecimal("0.02"), new BigDecimal("0.03"),
                new BigDecimal("0.07"), new BigDecimal("0.11"), new BigDecimal("0.13"),
                new BigDecimal("0.29"), new BigDecimal("1.00"), new BigDecimal("2.00")
        };

        List<Object[]> saleBatch = new ArrayList<>();
        List<Object[]> itemBatch = new ArrayList<>();
        BigDecimal expectedRevenue = BigDecimal.ZERO;
        long saleId = 30000L;
        long itemId = 300000L;

        for (int s = 0; s < 200; s++) {
            int lines = 1 + (s % 5);
            BigDecimal discount = discountChoices[s % discountChoices.length];
            Sale sale = new Sale();
            sale.setDiscountAmount(discount);
            List<SaleItem> items = new ArrayList<>();
            BigDecimal grossTotal = BigDecimal.ZERO;
            for (int i = 0; i < lines; i++) {
                BigDecimal gross = grossChoices[(s * 3 + i * 5) % grossChoices.length];
                grossTotal = grossTotal.add(gross);
                SaleItem item = new SaleItem();
                item.setId(itemId);
                item.setQuantity(1);
                item.setUnitPrice(gross);
                item.setTotalPrice(gross);
                item.setCostPriceAtSale(BigDecimal.ZERO);
                item.setQuantityRefunded(0);
                items.add(item);
                itemBatch.add(new Object[]{itemId, saleId, productId, 1, gross, gross,
                        BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO});
                itemId++;
            }
            sale.setItems(items);
            saleBatch.add(new Object[]{saleId, "PARITY-" + s, "Walk-in", 1L, at,
                    grossTotal, BigDecimal.ZERO, grossTotal.subtract(discount).max(BigDecimal.ZERO),
                    grossTotal, BigDecimal.ZERO, BigDecimal.ZERO,
                    "CASH", "CASH", "PAID", "COMPLETED", false, true,
                    discount, BigDecimal.ZERO, BigDecimal.ZERO, 0L, at, at});

            Map<SaleItem, BigDecimal> allocations = SaleDiscountUtils.allocateDiscounts(sale);
            for (SaleItem item : items) {
                BigDecimal net = item.getTotalPrice().subtract(allocations.getOrDefault(item, BigDecimal.ZERO));
                if (net.signum() > 0) {
                    expectedRevenue = expectedRevenue.add(net);
                }
            }
            saleId++;
        }
        jdbcTemplate.batchUpdate(saleSql, saleBatch);
        jdbcTemplate.batchUpdate(itemSql, itemBatch);

        Map<String, Object> summary = reportService.getLegacyProfitSummary(today, today);
        BigDecimal revenue = (BigDecimal) summary.get("revenue");
        assertEquals(0, expectedRevenue.compareTo(revenue),
                "expected " + expectedRevenue.toPlainString() + " but was " + revenue);
    }
}
