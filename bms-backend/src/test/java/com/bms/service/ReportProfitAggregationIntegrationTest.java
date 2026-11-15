package com.bms.service;

import com.bms.dto.response.AccountingSummaryResponse;
import com.bms.dto.response.DailySalesTrendDto;
import com.bms.entity.Product;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P7 acceptance (spec 6.3): with 60,000 sales in the range the reported
 * revenue, COGS and profit equal the exact totals — no silent 50k truncation.
 * Runs on an isolated in-memory H2 slice (no production data, no web context).
 */
@DataJpaTest
@ContextConfiguration(classes = ReportProfitAggregationIntegrationTest.JpaConfig.class)
@Import({ReportService.class, InventoryLossService.class, LedgerReadService.class, ShopTimeService.class})
class ReportProfitAggregationIntegrationTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private ReportService reportService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ShopTimeService shopTimeService;

    // ReportService injects these but the profit/accounting summaries never use them.
    @MockBean private CashShiftService cashShiftService;
    @MockBean private CashDrawerSessionService cashDrawerSessionService;
    @MockBean private SystemSettingService systemSettingService;

    private static final int BULK_SALES = 60_000;
    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private static final BigDecimal COST = new BigDecimal("60.000000");

    @Test
    void sixtyThousandSalesAggregateExactly() {
        Product product = new Product();
        product.setSku("P7-BULK");
        product.setName("Bulk product");
        product.setUnitPrice(PRICE);
        product.setCostPrice(COST);
        product.setStockQuantity(0);
        em.persist(product);
        em.flush();
        Long productId = product.getId();

        LocalDate today = shopTimeService.today();
        // All data lands in [today-12, today] so two WEEK buckets or one
        // 14-day sales trend always cover it, whatever day the suite runs.
        LocalDateTime[] dates = new LocalDateTime[13];
        for (int d = 0; d < 13; d++) {
            dates[d] = today.minusDays(d).atTime(10, 0);
        }

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

        List<Object[]> saleBatch = new ArrayList<>();
        List<Object[]> itemBatch = new ArrayList<>();
        int voidedCount = 0;
        for (int i = 1; i <= BULK_SALES; i++) {
            boolean voided = i % 1000 == 0;
            if (voided) {
                voidedCount++;
            }
            Timestamp ts = Timestamp.valueOf(dates[i % 13]);
            saleBatch.add(new Object[]{
                    (long) i, "INV-P7-" + i, "Walk-in", 1L, ts,
                    PRICE, BigDecimal.ZERO, PRICE, PRICE, BigDecimal.ZERO, BigDecimal.ZERO,
                    "CASH", "CASH", "PAID", "COMPLETED", voided, true,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L, ts, ts});
            itemBatch.add(new Object[]{
                    (long) i, (long) i, productId, 1, PRICE, PRICE,
                    BigDecimal.ZERO, COST, 0, BigDecimal.ZERO});
            if (saleBatch.size() == 5000) {
                jdbcTemplate.batchUpdate(saleSql, saleBatch);
                jdbcTemplate.batchUpdate(itemSql, itemBatch);
                saleBatch.clear();
                itemBatch.clear();
            }
        }
        if (!saleBatch.isEmpty()) {
            jdbcTemplate.batchUpdate(saleSql, saleBatch);
            jdbcTemplate.batchUpdate(itemSql, itemBatch);
        }

        // Fixture A: two lines, sale-level discount 13 over gross 130.
        // Shares: line1 = 10.00, line2 (remainder) = 3.00 -> revenue 90 + 27 = 117.
        Timestamp fixtureTs = Timestamp.valueOf(today.atTime(11, 0));
        jdbcTemplate.update(saleSql, 60001L, "INV-P7-A", "Walk-in", 1L, fixtureTs,
                new BigDecimal("130.00"), BigDecimal.ZERO, new BigDecimal("130.00"),
                new BigDecimal("130.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                "CASH", "CASH", "PAID", "COMPLETED", false, true,
                new BigDecimal("13.00"), BigDecimal.ZERO, BigDecimal.ZERO, 0L, fixtureTs, fixtureTs);
        jdbcTemplate.update(itemSql, 600011L, 60001L, productId, 2,
                new BigDecimal("50.00"), new BigDecimal("100.00"), BigDecimal.ZERO, COST, 0, BigDecimal.ZERO);
        jdbcTemplate.update(itemSql, 600012L, 60001L, productId, 1,
                new BigDecimal("30.00"), new BigDecimal("30.00"), BigDecimal.ZERO, COST, 0, BigDecimal.ZERO);

        // Fixture B: qty 3 @ 100, one unit already refunded -> sold 2 -> revenue 200, cogs 120.
        jdbcTemplate.update(saleSql, 60002L, "INV-P7-B", "Walk-in", 1L, fixtureTs,
                new BigDecimal("300.00"), BigDecimal.ZERO, new BigDecimal("300.00"),
                new BigDecimal("300.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                "CASH", "CASH", "PAID", "COMPLETED", false, true,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L, fixtureTs, fixtureTs);
        jdbcTemplate.update(itemSql, 600021L, 60002L, productId, 3,
                PRICE, new BigDecimal("300.00"), BigDecimal.ZERO, COST, 1, BigDecimal.ZERO);

        int liveBulk = BULK_SALES - voidedCount;
        BigDecimal expectedRevenue = PRICE.multiply(BigDecimal.valueOf(liveBulk))
                .add(new BigDecimal("117.00")).add(new BigDecimal("200.00"));
        BigDecimal expectedCogs = COST.multiply(BigDecimal.valueOf(liveBulk))
                .add(new BigDecimal("180.000000")).add(new BigDecimal("120.000000"));
        BigDecimal expectedProfit = expectedRevenue.subtract(expectedCogs);
        long expectedTransactions = liveBulk + 2L;

        LocalDate start = today.minusDays(12);

        Map<String, Object> summary = reportService.getProfitSummary(start, today);
        assertMoney(expectedRevenue, (BigDecimal) summary.get("revenue"));
        assertMoney(expectedCogs, (BigDecimal) summary.get("cogs"));
        assertMoney(expectedProfit, (BigDecimal) summary.get("grossProfit"));

        // Two rolling WEEK buckets cover exactly [today-13, today].
        List<Map<String, Object>> weekTrend = reportService.getProfitTrend("WEEK", 2);
        assertEquals(2, weekTrend.size());
        BigDecimal weekRevenue = BigDecimal.ZERO;
        BigDecimal weekCogs = BigDecimal.ZERO;
        for (Map<String, Object> point : weekTrend) {
            weekRevenue = weekRevenue.add((BigDecimal) point.get("revenue"));
            weekCogs = weekCogs.add((BigDecimal) point.get("cogs"));
        }
        assertMoney(expectedRevenue, weekRevenue);
        assertMoney(expectedCogs, weekCogs);

        // Two YEAR buckets cover any new-year boundary.
        List<Map<String, Object>> yearTrend = reportService.getProfitTrend("YEAR", 2);
        BigDecimal yearRevenue = BigDecimal.ZERO;
        for (Map<String, Object> point : yearTrend) {
            yearRevenue = yearRevenue.add((BigDecimal) point.get("revenue"));
        }
        assertMoney(expectedRevenue, yearRevenue);

        AccountingSummaryResponse accounting = reportService.getAccountingSummary(start, today);
        assertMoney(expectedRevenue, accounting.getTotalIncome());
        assertMoney(expectedCogs, accounting.getTotalCogs());
        assertMoney(expectedProfit, accounting.getGrossProfit());

        // Daily trend uses netSaleRevenue per sale; 14 days cover all fixtures.
        List<DailySalesTrendDto> dailyTrend = reportService.getSalesTrend(14);
        BigDecimal dailyRevenue = BigDecimal.ZERO;
        long dailyTransactions = 0;
        for (DailySalesTrendDto dto : dailyTrend) {
            dailyRevenue = dailyRevenue.add(dto.getTotalSales());
            dailyTransactions += dto.getTransactionCount();
        }
        assertMoney(expectedRevenue, dailyRevenue);
        assertEquals(expectedTransactions, dailyTransactions);
    }

    private static void assertMoney(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                "expected " + expected.toPlainString() + " but was " + actual);
    }
}