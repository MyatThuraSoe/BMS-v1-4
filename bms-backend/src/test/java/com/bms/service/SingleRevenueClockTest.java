package com.bms.service;

import com.bms.entity.Product;
import com.bms.entity.ProfitLedger;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

/**
 * Audit item 10: every revenue report must use one clock — the profit ledger's
 * {@code occurred_at}. A back-posted sale and a same-day return must land on
 * their event dates, not on {@code posted_at} and not on mutable sale items.
 * Runs on an isolated in-memory H2 slice (no production data, no web context).
 */
@DataJpaTest
@ContextConfiguration(classes = SingleRevenueClockTest.JpaConfig.class)
@Import({ReportService.class, InventoryLossService.class, LedgerReadService.class, ShopTimeService.class})
class SingleRevenueClockTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private ReportService reportService;
    @Autowired private ShopTimeService shopTimeService;

    // Same collaborators as ReportProfitAggregationIntegrationTest: ReportService
    // injects them, but the daily report never touches them.
    @MockBean private CashShiftService cashShiftService;
    @MockBean private CashDrawerSessionService cashDrawerSessionService;
    @MockBean private SystemSettingService systemSettingService;
    @MockBean private SystemSettingRepository systemSettingRepository;

    @Test
    void dailyReportIsDatedByLedgerOccurredAt() {
        LocalDate today = shopTimeService.today();
        LocalDate saleDate = today.minusMonths(1);

        // Ledger reads are active. The repository is mocked in this slice, so
        // point the lookup at the persisted, nonblank cutover setting.
        SystemSetting cutover = new SystemSetting();
        cutover.setSettingKey(LedgerReadService.CUTOVER_KEY);
        cutover.setSettingValue(saleDate.atStartOfDay().toString());
        em.persist(cutover);
        em.flush();
        lenient().when(systemSettingRepository.findBySettingKey(LedgerReadService.CUTOVER_KEY))
                .thenReturn(Optional.of(cutover));

        Product product = new Product();
        product.setSku("A10-CLOCK");
        product.setName("Ledger clock fixture");
        product.setUnitPrice(new BigDecimal("100.00"));
        product.setCostPrice(new BigDecimal("60.000000"));
        product.setStockQuantity(0);
        em.persist(product);
        em.flush();
        Long productId = product.getId();

        // Sale occurred a month ago but was posted today: only occurred_at may
        // decide which daily report it appears in.
        em.persist(ledgerRow(productId, "SALE", ProfitLedger.EventType.SALE, 9101L,
                saleDate.atTime(10, 0), today.atTime(12, 0),
                new BigDecimal("100.0000"), new BigDecimal("60.0000"), 1));
        // Return occurred today and posts next morning: it must reduce today's
        // revenue and COGS by exactly the original sale amounts.
        em.persist(ledgerRow(productId, "SALE_RETURN", ProfitLedger.EventType.RETURN, 9102L,
                today.atTime(15, 0), today.plusDays(1).atTime(9, 0),
                new BigDecimal("-100.0000"), new BigDecimal("-60.0000"), -1));
        em.flush();

        Map<String, Object> saleDay = reportService.getDailySalesReport(saleDate);
        assertMoney(new BigDecimal("100.0000"), (BigDecimal) saleDay.get("totalRevenue"),
                "sale-day revenue must come from the SALE row dated by occurred_at");
        assertMoney(new BigDecimal("60.0000"), (BigDecimal) saleDay.get("totalCost"),
                "sale-day cost must come from the SALE row dated by occurred_at");

        Map<String, Object> todayReport = reportService.getDailySalesReport(today);
        assertMoney(new BigDecimal("-100.0000"), (BigDecimal) todayReport.get("totalRevenue"),
                "today's revenue must include the SALE_RETURN row dated by occurred_at");
        assertMoney(new BigDecimal("-60.0000"), (BigDecimal) todayReport.get("totalCost"),
                "today's cost must include the SALE_RETURN row dated by occurred_at");
    }

    private static ProfitLedger ledgerRow(Long productId, String sourceType, ProfitLedger.EventType eventType,
                                          long sourceId, LocalDateTime occurredAt, LocalDateTime postedAt,
                                          BigDecimal netRevenue, BigDecimal cogs, int quantity) {
        ProfitLedger row = new ProfitLedger();
        row.setPeriod(YearMonth.from(occurredAt).toString());
        row.setEventType(eventType);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setProductId(productId);
        row.setLineKey("line-" + sourceId);
        row.setQuantity(quantity);
        row.setNetRevenue(netRevenue);
        row.setCogs(cogs);
        row.setExpense(BigDecimal.ZERO);
        row.setTaxAmount(BigDecimal.ZERO);
        row.setOccurredAt(occurredAt);
        row.setPostedAt(postedAt);
        return row;
    }

    private static void assertMoney(BigDecimal expected, BigDecimal actual, String message) {
        String found = actual == null ? "null" : actual.toPlainString();
        assertEquals(0, expected.compareTo(actual),
                message + " (expected " + expected.toPlainString() + " but was " + found + ")");
    }
}
