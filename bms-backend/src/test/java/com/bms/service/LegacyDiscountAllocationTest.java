package com.bms.service;

import com.bms.entity.Product;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.lenient;

/**
 * Audit 17: the SQL profit path must allocate a sale-level discount exactly as
 * {@link SaleDiscountUtils} does. Four 1.00 lines with a 0.02 sale discount get
 * 0.01 on each of the first three lines, then the Java allocator removes one
 * cent so the shares add up to 0.02 and revenue is 3.98. The SQL currently
 * keeps all three cents (the remainder line cannot go negative), so the legacy
 * report shows 3.97.
 */
@DataJpaTest
@ContextConfiguration(classes = LegacyDiscountAllocationTest.JpaConfig.class)
@Import({ReportService.class, InventoryLossService.class, LedgerReadService.class, ShopTimeService.class})
class LegacyDiscountAllocationTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private ReportService reportService;
    @Autowired private LedgerReadService ledgerReadService;
    @Autowired private ShopTimeService shopTimeService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private CashShiftService cashShiftService;
    @MockBean private CashDrawerSessionService cashDrawerSessionService;
    @MockBean private SystemSettingService systemSettingService;
    @MockBean private SystemSettingRepository systemSettingRepository;

    @Test
    void fourEqualLinesAllocateExactlyTwoCentsOfDiscount() {
        Product product = new Product();
        product.setSku("AUDIT17-FOUR-LINES");
        product.setName("Four-line fixture");
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
        // No cutover row: ledger reads stay inactive and the legacy SQL path is used.
        lenient().when(systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY))
                .thenReturn(Optional.empty());
        lenient().when(systemSettingRepository.findBySettingKey(ShopTimeService.TIMEZONE_KEY))
                .thenReturn(Optional.empty());

        assertFalse(ledgerReadService.isActive(), "cutover must be absent for this legacy fixture");

        LocalDate today = shopTimeService.today();
        Timestamp at = Timestamp.valueOf(today.atTime(10, 0));
        BigDecimal gross = new BigDecimal("4.00");
        BigDecimal discount = new BigDecimal("0.02");

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

        jdbcTemplate.update(saleSql, 10000L, "AUDIT17-1", "Walk-in", 1L, at,
                gross, BigDecimal.ZERO, new BigDecimal("3.98"), new BigDecimal("3.98"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                "CASH", "CASH", "PAID", "COMPLETED", false, true,
                discount, BigDecimal.ZERO, BigDecimal.ZERO, 0L, at, at);
        // Ascending ids matter: MAX(id) is the remainder line in both allocators.
        for (int i = 1; i <= 4; i++) {
            jdbcTemplate.update(itemSql, 10000L + i, 10000L, productId, 1,
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        }

        Map<String, Object> summary = reportService.getLegacyProfitSummary(today, today);
        BigDecimal revenue = (BigDecimal) summary.get("revenue");
        assertEquals(0, new BigDecimal("3.98").compareTo(revenue),
                "expected 3.98 after the 0.02 discount but was " + revenue);
    }
}
