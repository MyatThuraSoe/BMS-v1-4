package com.bms.service;

import com.bms.dto.response.AccountingSummaryResponse;
import com.bms.dto.response.BasketAffinityDto;
import com.bms.dto.response.CashShiftResponse;
import com.bms.dto.response.CustomerLtvDto;
import com.bms.dto.response.CustomerRetentionDto;
import com.bms.dto.response.DailySalesTrendDto;
import com.bms.dto.response.DeadStockDto;
import com.bms.dto.response.DrawerSessionResponse;
import com.bms.dto.response.SalesTimingDto;
import com.bms.dto.response.SupplierProfitDto;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.Expense;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SalePayment;
import com.bms.entity.SaleReturn;
import com.bms.repository.CashDrawerSessionRepository;
import com.bms.repository.ExpenseRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.util.CashVarianceUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ReportService {

    @Autowired
    private SystemSettingService systemSettingService;

    /**
     * P7: aggregate reports (profit, trends, daily/monthly, product/category,
     * cashier, timing) now run as SQL aggregations so totals are exact at any
     * volume. Only getEndOfDayReport still loads a bounded page because it needs
     * the full entity graph for per-sale exception checks; it reports the cap
     * through the {@code truncated} / {@code rowCount} fields so the UI can warn
     * instead of silently dropping rows.
     */
    private static final int REPORT_MAX_ROWS = 50_000;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private InventoryLossService inventoryLossService;

    @Autowired
    private LedgerReadService ledgerReadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private SaleReturnRepository saleReturnRepository;

    @Autowired
    private SaleItemRepository saleItemRepository;

    @Autowired
    private SalePaymentRepository salePaymentRepository;

    @Autowired
    private CashDrawerSessionRepository cashDrawerSessionRepository;

    @Autowired
    private CashShiftService cashShiftService;

    @Autowired
    private CashDrawerSessionService cashDrawerSessionService;

    @Autowired
    private ShopTimeService shopTimeService;

    // Daily sales report
    public Map<String, Object> getDailySalesReport(LocalDate date) {
        LocalDateTime startOfDay = shopTimeService.startOfDay(date);
        LocalDateTime endOfDay = shopTimeService.endOfDayExclusive(date);

        String sql = "SELECT l.product_id, COALESCE(MAX(p.name), '') AS product_name, "
                + "COALESCE(MAX(p.sku), '') AS sku, SUM(l.qty_sold) AS quantity_sold, "
                + "SUM(l.line_revenue) AS revenue, SUM(l.prorated_tax) AS tax, SUM(l.line_cogs) AS cost "
                + "FROM (" + lineRevenueSql() + ") l LEFT JOIN products p ON p.id = l.product_id "
                + "WHERE l.qty_sold > 0 GROUP BY l.product_id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, startOfDay, endOfDay);

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        long totalItemsSold = 0;
        List<Map<String, Object>> itemSales = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int quantitySold = ((Number) row.get("quantity_sold")).intValue();
            BigDecimal revenue = toBigDecimal(row.get("revenue"));
            BigDecimal cost = toBigDecimal(row.get("cost"));
            totalRevenue = totalRevenue.add(revenue);
            totalCost = totalCost.add(cost);
            totalItemsSold += quantitySold;
            BigDecimal unitPrice = quantitySold > 0
                    ? revenue.divide(BigDecimal.valueOf(quantitySold), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            Map<String, Object> item = new HashMap<>();
            item.put("productId", ((Number) row.get("product_id")).longValue());
            item.put("productName", row.get("product_name"));
            item.put("sku", row.get("sku"));
            item.put("quantitySold", quantitySold);
            item.put("unitPrice", unitPrice);
            item.put("taxAmount", toBigDecimal(row.get("tax")));
            item.put("totalPrice", revenue);
            item.put("costAmount", cost);
            item.put("profit", revenue.subtract(cost));
            itemSales.add(item);
        }
        itemSales.sort(Comparator.comparing((Map<String, Object> r) ->
                toBigDecimal(r.get("totalPrice"))).reversed());

        Long transactions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sales s WHERE s.is_active = true AND s.deleted_at IS NULL "
                        + "AND s.is_voided = false AND s.sale_date >= ? AND s.sale_date < ?",
                Long.class, startOfDay, endOfDay);
        int totalTransactions = transactions != null ? transactions.intValue() : 0;

        Map<String, Object> report = new HashMap<>();
        report.put("date", date);
        report.put("totalRevenue", totalRevenue);
        report.put("totalCost", totalCost);
        report.put("totalProfit", totalRevenue.subtract(totalCost));
        report.put("totalItemsSold", totalItemsSold);
        report.put("totalTransactions", totalTransactions);
        report.put("averageTransactionValue", totalTransactions > 0 ? totalRevenue.divide(BigDecimal.valueOf(totalTransactions), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);
        report.put("itemSales", itemSales);

        return report;
    }

    // Monthly sales report
    public Map<String, Object> getMonthlySalesReport(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);

        LocalDateTime startDateTime = shopTimeService.startOfDay(startDate);
        LocalDateTime endDateTime = shopTimeService.endOfDayExclusive(endDate);

        String sql = "SELECT COALESCE(SUM(t.sale_revenue), 0), COUNT(*) FROM ("
                + perSaleRevenueSql() + ") t";
        BigDecimal[] totals = jdbcTemplate.queryForObject(sql,
                (rs, rowNum) -> new BigDecimal[]{
                        toBigDecimal(rs.getObject(1)), BigDecimal.valueOf(rs.getLong(2))},
                startDateTime, endDateTime);
        BigDecimal totalRevenue = totals != null ? totals[0] : BigDecimal.ZERO;
        int totalTransactions = totals != null ? totals[1].intValue() : 0;

        Map<String, Object> report = new HashMap<>();
        report.put("year", year);
        report.put("month", month);
        report.put("totalRevenue", totalRevenue);
        report.put("totalTransactions", totalTransactions);
        report.put("averageTransactionValue", totalTransactions > 0 ? totalRevenue.divide(BigDecimal.valueOf(totalTransactions), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);

        return report;
    }

    // Product performance report
    public List<Map<String, Object>> getProductPerformanceReport(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = shopTimeService.startOfDay(startDate);
        LocalDateTime endDateTime = shopTimeService.endOfDayExclusive(endDate);

        String sql = "SELECT l.product_id, COALESCE(MAX(p.name), '') AS product_name, "
                + "SUM(l.qty_sold) AS quantity_sold, SUM(l.line_revenue) AS revenue "
                + "FROM (" + lineRevenueSql() + ") l LEFT JOIN products p ON p.id = l.product_id "
                + "WHERE l.qty_sold > 0 GROUP BY l.product_id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, startDateTime, endDateTime);

        List<Map<String, Object>> report = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> stats = new HashMap<>();
            stats.put("productId", ((Number) row.get("product_id")).longValue());
            stats.put("productName", row.get("product_name"));
            stats.put("totalQuantitySold", ((Number) row.get("quantity_sold")).intValue());
            stats.put("totalRevenue", toBigDecimal(row.get("revenue")));
            report.add(stats);
        }
        report.sort((a, b) -> ((BigDecimal) b.get("totalRevenue")).compareTo((BigDecimal) a.get("totalRevenue")));

        return report;
    }

    // Top selling products
    public List<Map<String, Object>> getTopSellingProducts(int limit, LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> allProducts = getProductPerformanceReport(startDate, endDate);
        
        if (allProducts.size() > limit) {
            return allProducts.subList(0, limit);
        }
        return allProducts;
    }

    // -----------------------------------------------------------------------
    // Section 6: cashier performance. Accumulate all items and sales before computing averages.
    // -----------------------------------------------------------------------
    public List<Map<String, Object>> getCashierPerformanceReport(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = shopTimeService.startOfDay(startDate);
        LocalDateTime endDateTime = shopTimeService.endOfDayExclusive(endDate);

        String sql = "SELECT t.cashier_id, SUM(t.sale_revenue) AS total_sales, "
                + "COUNT(*) AS tx_count, SUM(t.items_sold) AS item_count "
                + "FROM (" + perSaleRevenueSql() + ") t GROUP BY t.cashier_id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, startDateTime, endDateTime);

        List<Map<String, Object>> report = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> stats = new HashMap<>();
            stats.put("cashierId", ((Number) row.get("cashier_id")).longValue());
            BigDecimal totalSales = toBigDecimal(row.get("total_sales"));
            int txCount = ((Number) row.get("tx_count")).intValue();
            int totalItems = ((Number) row.get("item_count")).intValue();
            stats.put("totalSales", totalSales);
            stats.put("transactionCount", txCount);
            stats.put("totalItems", totalItems);
            if (txCount > 0) {
                stats.put("averageTransactionValue",
                        totalSales.divide(BigDecimal.valueOf(txCount), 2, RoundingMode.HALF_UP));
                stats.put("averageItemsPerSale", (double) totalItems / txCount);
            } else {
                stats.put("averageTransactionValue", BigDecimal.ZERO);
                stats.put("averageItemsPerSale", 0.0);
            }
            report.add(stats);
        }
        report.sort((a, b) -> ((BigDecimal) b.get("totalSales")).compareTo((BigDecimal) a.get("totalSales")));

        return report;
    }

    public List<DailySalesTrendDto> getSalesTrend(int days) {
        LocalDate endDate = shopTimeService.today();
        LocalDate startDate = endDate.minusDays(Math.max(days, 1) - 1);
        LocalDateTime startDateTime = shopTimeService.startOfDay(startDate);
        LocalDateTime endDateTime = shopTimeService.endOfDayExclusive(endDate);

        Map<LocalDate, DailySalesTrendDto> trendByDate = new LinkedHashMap<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            trendByDate.put(date, new DailySalesTrendDto(date, BigDecimal.ZERO, 0));
        }

        String sql = "SELECT t.sale_date, t.sale_revenue FROM (" + perSaleRevenueSql() + ") t";
        jdbcTemplate.query(sql, rs -> {
            LocalDate saleDate = toLocalDateTime(rs.getObject("sale_date")).toLocalDate();
            DailySalesTrendDto dto = trendByDate.get(saleDate);
            if (dto != null) {
                dto.setTotalSales(dto.getTotalSales().add(toBigDecimal(rs.getObject("sale_revenue"))));
                dto.setTransactionCount(dto.getTransactionCount() + 1);
            }
        }, startDateTime, endDateTime);

        return new ArrayList<>(trendByDate.values());
    }

    public List<Map<String, Object>> getTopProducts(String period, int limit, String compareMode) {
        LocalDate endDate = shopTimeService.today();
        LocalDate startDate = resolveStartDate(period, endDate);
        LocalDate[] compareRange = resolveComparisonRange(period, compareMode, startDate, endDate);

        Map<Long, BigDecimal> currentRevenueMap = computeProductRevenue(startDate, endDate);
        Map<Long, BigDecimal> previousRevenueMap = computeProductRevenue(compareRange[0], compareRange[1]);

        List<Map<String, Object>> report = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : currentRevenueMap.entrySet()) {
            Long productId = entry.getKey();
            BigDecimal currentRev = entry.getValue();
            BigDecimal prevRev = previousRevenueMap.getOrDefault(productId, BigDecimal.ZERO);

            BigDecimal changePercent = null;
            if (prevRev.compareTo(BigDecimal.ZERO) > 0) {
                changePercent = currentRev.subtract(prevRev)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(prevRev, 2, RoundingMode.HALF_UP);
            }

            // Fetch product name safely
            String productName = "Unknown";
            var prodOpt = productRepository.findById(productId);
            if (prodOpt.isPresent()) {
                productName = prodOpt.get().getName();
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", productId);
            item.put("productName", productName);
            item.put("revenue", currentRev);
            item.put("previousRevenue", prevRev);
            item.put("changePercent", changePercent);
            report.add(item);
        }

        report.sort((a, b) -> ((BigDecimal) b.get("revenue")).compareTo((BigDecimal) a.get("revenue")));
        return report.stream().limit(limit).toList();
    }

        public List<Map<String, Object>> getTopCategories(String period, String compareMode) {
        LocalDate endDate = shopTimeService.today();
        LocalDate startDate = resolveStartDate(period, endDate);
        LocalDate[] compareRange = resolveComparisonRange(period, compareMode, startDate, endDate);

        // 1. Get current period data (and capture category names)
        Map<Long, String> categoryNames = new HashMap<>();
        Map<Long, BigDecimal> currentRevenueMap = new HashMap<>();

        String currentSql = "SELECT COALESCE(c.id, -1) AS category_id, "
                + "COALESCE(MAX(c.name), 'Uncategorized') AS category_name, "
                + "SUM(l.line_revenue) AS revenue "
                + "FROM (" + lineRevenueSql() + ") l "
                + "LEFT JOIN products p ON p.id = l.product_id "
                + "LEFT JOIN categories c ON c.id = p.category_id "
                + "WHERE l.qty_sold > 0 GROUP BY COALESCE(c.id, -1)";
        List<Map<String, Object>> currentRows = jdbcTemplate.queryForList(currentSql,
                shopTimeService.startOfDay(startDate), shopTimeService.endOfDayExclusive(endDate));
        for (Map<String, Object> row : currentRows) {
            Long categoryId = ((Number) row.get("category_id")).longValue();
            categoryNames.put(categoryId, (String) row.get("category_name"));
            currentRevenueMap.put(categoryId, toBigDecimal(row.get("revenue")));
        }

        // 2. Get previous period data using the reusable helper
        Map<Long, BigDecimal> previousRevenueMap = computeCategoryRevenue(compareRange[0], compareRange[1]);

        // 3. Build the final report with change percentages
        List<Map<String, Object>> report = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : currentRevenueMap.entrySet()) {
            Long categoryId = entry.getKey();
            BigDecimal currentRev = entry.getValue();
            BigDecimal prevRev = previousRevenueMap.getOrDefault(categoryId, BigDecimal.ZERO);

            BigDecimal changePercent = null;
            if (prevRev.compareTo(BigDecimal.ZERO) > 0) {
                changePercent = currentRev.subtract(prevRev)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(prevRev, 2, RoundingMode.HALF_UP);
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("categoryId", categoryId == -1L ? null : categoryId);
            item.put("categoryName", categoryNames.getOrDefault(categoryId, "Uncategorized"));
            item.put("revenue", currentRev);
            item.put("previousRevenue", prevRev);
            item.put("changePercent", changePercent);
            report.add(item);
        }

        report.sort((a, b) -> ((BigDecimal) b.get("revenue")).compareTo((BigDecimal) a.get("revenue")));
        return report;
    }

    /**
     * Daily revenue per selected category over the current period, for the
     * Analytics line chart. Each row is { date, category_<id>: amount }.
     */
    public List<Map<String, Object>> getCategoryComparison(List<Long> categoryIds, String period) {
        if (categoryIds == null || categoryIds.size() < 2) {
            return List.of();
        }
        LocalDate endDate = shopTimeService.today();
        LocalDate startDate = resolveStartDate(period, endDate);

        StringBuilder placeholders = new StringBuilder();
        List<Object> params = new ArrayList<>();
        params.add(shopTimeService.startOfDay(startDate));
        params.add(shopTimeService.endOfDayExclusive(endDate));
        for (int i = 0; i < categoryIds.size(); i++) {
            placeholders.append(i == 0 ? "?" : ",?");
            params.add(categoryIds.get(i));
        }
        String sql = "SELECT l.sale_id, l.sale_date, COALESCE(c.id, -1) AS category_id, "
                + "SUM(l.line_revenue) AS revenue "
                + "FROM (" + lineRevenueSql() + ") l "
                + "LEFT JOIN products p ON p.id = l.product_id "
                + "LEFT JOIN categories c ON c.id = p.category_id "
                + "WHERE l.qty_sold > 0 AND COALESCE(c.id, -1) IN (" + placeholders + ") "
                + "GROUP BY l.sale_id, l.sale_date, COALESCE(c.id, -1)";

        // day -> (categoryId -> revenue)
        Map<LocalDate, Map<Long, BigDecimal>> daily = new TreeMap<>();
        jdbcTemplate.query(sql, rs -> {
            LocalDate day = toLocalDateTime(rs.getObject("sale_date")).toLocalDate();
            Long categoryId = ((Number) rs.getObject("category_id")).longValue();
            BigDecimal revenue = toBigDecimal(rs.getObject("revenue"));
            daily.computeIfAbsent(day, k -> new HashMap<>())
                    .merge(categoryId, revenue, BigDecimal::add);
        }, params.toArray());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<Long, BigDecimal>> dayEntry : daily.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", dayEntry.getKey().toString());
            for (Long id : categoryIds) {
                row.put("category_" + id, dayEntry.getValue().getOrDefault(id, BigDecimal.ZERO));
            }
            result.add(row);
        }
        return result;
    }

    public Map<String, Object> getProfitSummary(LocalDate startDate, LocalDate endDate) {
        if (!ledgerReadService.isActive()) return getLegacyProfitSummary(startDate, endDate);
        BigDecimal[] totals = ledgerReadService.totals(shopTimeService.startOfDay(startDate), shopTimeService.endOfDayExclusive(endDate));
        BigDecimal grossProfit = totals[0].subtract(totals[1]);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("revenue", totals[0]);
        summary.put("cogs", totals[1]);
        summary.put("grossProfit", grossProfit);
        summary.put("grossMarginPercent", totals[0].signum() > 0
                ? grossProfit.multiply(BigDecimal.valueOf(100)).divide(totals[0], 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        summary.put("outstandingAr", saleRepository.sumOutstandingAr());
        return summary;
    }

    Map<String, Object> getLegacyProfitSummary(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = shopTimeService.startOfDay(startDate);
        LocalDateTime endDateTime = shopTimeService.endOfDayExclusive(endDate);

        // P7: aggregate in SQL so any volume reports exactly — no row cap.
        String sql = "SELECT COALESCE(SUM(l.line_revenue), 0), COALESCE(SUM(l.line_cogs), 0) FROM ("
                + lineRevenueSql() + ") l";
        BigDecimal[] totals = jdbcTemplate.queryForObject(sql,
                (rs, rowNum) -> new BigDecimal[]{
                        toBigDecimal(rs.getObject(1)), toBigDecimal(rs.getObject(2))},
                startDateTime, endDateTime);
        BigDecimal revenue = totals != null ? totals[0] : BigDecimal.ZERO;
        BigDecimal cogs = totals != null ? totals[1] : BigDecimal.ZERO;

        BigDecimal grossProfit = revenue.subtract(cogs);
        BigDecimal grossMarginPercent = revenue.compareTo(BigDecimal.ZERO) > 0
                ? grossProfit.multiply(BigDecimal.valueOf(100)).divide(revenue, 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("revenue", revenue);
        summary.put("cogs", cogs);
        summary.put("grossProfit", grossProfit);
        summary.put("grossMarginPercent", grossMarginPercent);
        summary.put("outstandingAr", saleRepository.sumOutstandingAr());
        return summary;
    }

    public List<Map<String, Object>> getProfitTrend(String period, int points) {
        LocalDate today = shopTimeService.today();
        String normalizedPeriod = period != null ? period.toUpperCase(Locale.ROOT) : "MONTH";
        int safePoints = Math.max(points, 1);

        LocalDate[] bucketStarts = new LocalDate[safePoints];
        LocalDate[] bucketEnds = new LocalDate[safePoints];
        String[] labels = new String[safePoints];
        for (int i = safePoints - 1; i >= 0; i--) {
            if ("WEEK".equals(normalizedPeriod)) {
                bucketEnds[i] = today.minusWeeks(i);
                bucketStarts[i] = bucketEnds[i].minusDays(6);
                labels[i] = bucketStarts[i] + " - " + bucketEnds[i];
            } else if ("YEAR".equals(normalizedPeriod)) {
                int year = today.getYear() - i;
                bucketStarts[i] = LocalDate.of(year, 1, 1);
                bucketEnds[i] = LocalDate.of(year, 12, 31);
                labels[i] = String.valueOf(year);
            } else {
                YearMonth yearMonth = YearMonth.from(today).minusMonths(i);
                bucketStarts[i] = yearMonth.atDay(1);
                bucketEnds[i] = yearMonth.atEndOfMonth();
                labels[i] = yearMonth.toString();
            }
        }

        BigDecimal[] revenues = new BigDecimal[safePoints];
        BigDecimal[] cogs = new BigDecimal[safePoints];
        Arrays.fill(revenues, BigDecimal.ZERO);
        Arrays.fill(cogs, BigDecimal.ZERO);

        // P7: ONE grouped query over the whole range, bucketed in memory —
        // never one query per bucket, never a capped entity-graph load.
        LocalDateTime rangeStart = shopTimeService.startOfDay(bucketStarts[safePoints - 1]);
        LocalDateTime rangeEnd = shopTimeService.endOfDayExclusive(bucketEnds[0]);
        String sql = ledgerReadService.isActive()
                ? "SELECT occurred_at AS sale_date, SUM(net_revenue) AS revenue, SUM(cogs) AS cogs FROM profit_ledger "
                    + "WHERE occurred_at >= ? AND occurred_at < ? GROUP BY occurred_at"
                : "SELECT t.sale_id, t.sale_date, SUM(t.line_revenue) AS revenue, SUM(t.line_cogs) AS cogs FROM ("
                    + lineRevenueSql() + ") t GROUP BY t.sale_id, t.sale_date";
        jdbcTemplate.query(sql, rs -> {
            LocalDate day = toLocalDateTime(rs.getObject("sale_date")).toLocalDate();
            BigDecimal revenue = toBigDecimal(rs.getObject("revenue"));
            BigDecimal cogsValue = toBigDecimal(rs.getObject("cogs"));
            for (int i = 0; i < safePoints; i++) {
                if (!day.isBefore(bucketStarts[i]) && !day.isAfter(bucketEnds[i])) {
                    revenues[i] = revenues[i].add(revenue);
                    cogs[i] = cogs[i].add(cogsValue);
                    break;
                }
            }
        }, rangeStart, rangeEnd);

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = safePoints - 1; i >= 0; i--) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("periodLabel", labels[i]);
            point.put("revenue", revenues[i]);
            point.put("cogs", cogs[i]);
            point.put("grossProfit", revenues[i].subtract(cogs[i]));
            trend.add(point);
        }
        return trend;
    }

    public AccountingSummaryResponse getAccountingSummary(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);
        return getAccountingSummary(startDate, endDate);
    }

    // -----------------------------------------------------------------------
    // Section 5: period-over-period comparison; populates previous-period fields.
    // -----------------------------------------------------------------------
    public AccountingSummaryResponse getAccountingSummary(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> profitSummary = getProfitSummary(startDate, endDate);
        LocalDateTime startDateTime = shopTimeService.startOfDay(startDate);
        LocalDateTime endDateTime = shopTimeService.endOfDayExclusive(endDate);

        boolean ledgerActive = ledgerReadService.isActive();
        Map<String, BigDecimal> byCategory = ledgerActive
                ? ledgerReadService.expensesByCategory(startDateTime, endDateTime, false) : new LinkedHashMap<>();
        BigDecimal totalExpenses = byCategory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Expense expense : ledgerActive ? List.<Expense>of() : expenseRepository.findFiltered(null, startDate, endDate)) {
            // O4: capitalized costs live on the purchase (inventory/freight), not operating P&L.
            if (expense.getDeletedAt() != null || expense.isCapitalized()) {
                continue;
            }
            totalExpenses = totalExpenses.add(expense.getAmount());
            byCategory.merge(expense.getCategory() != null ? expense.getCategory().name() : "OTHER",
                    expense.getAmount(), BigDecimal::add);
        }

        // P3: operating inventory losses (write-offs, damage, theft, count shrinkage)
        // are expenses too. Same shared source the GL journal reads, so I4 holds.
        Map<String, BigDecimal> inventoryLosses = ledgerActive
                ? ledgerReadService.expensesByCategory(startDateTime, endDateTime, true)
                : inventoryLossService.lossesByReason(startDateTime, endDateTime);
        BigDecimal totalInventoryLoss = BigDecimal.ZERO;
        List<AccountingSummaryResponse.ExpenseCategorySummary> lossSummaries = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> lossEntry : inventoryLosses.entrySet()) {
            totalInventoryLoss = totalInventoryLoss.add(lossEntry.getValue());
            AccountingSummaryResponse.ExpenseCategorySummary lossSummary =
                    new AccountingSummaryResponse.ExpenseCategorySummary();
            lossSummary.setCategory(lossEntry.getKey());
            lossSummary.setAmount(lossEntry.getValue());
            lossSummaries.add(lossSummary);
        }
        totalExpenses = totalExpenses.add(totalInventoryLoss);
        // When ledger reads are active, totalExpenses is the sum of byCategory
        // (EXPENSE + PURCHASE_VARIANCE) plus inventory losses (WRITE_OFF).
        // INVENTORY_GAIN rows are negative expenses in totals()[2] and are
        // excluded here so the total equals the sum of its category lines;
        // gains are reported separately via postInventoryGain in the GL.

        // ONE revenue definition across every report: tax-EXCLUSIVE and already
        // net of returns, identical to the Daily Sales report and Profit summary.
        // The old tax-inclusive gross disagreed with the daily "Total Revenue" and,
        // when combined with tax-exclusive COGS, counted collected sales tax as profit.
        BigDecimal totalIncome = (BigDecimal) profitSummary.get("revenue");
        BigDecimal totalCogs = (BigDecimal) profitSummary.get("cogs");
        BigDecimal grossProfit = totalIncome.subtract(totalCogs);

        // Informational only — revenue above is already net of returns, so refunds
        // are NOT subtracted a second time from gross profit.
        BigDecimal totalRefunds = BigDecimal.ZERO;
        for (SaleReturn saleReturn : saleReturnRepository.findByReturnDateBetween(startDateTime, endDateTime)) {
            totalRefunds = totalRefunds.add(saleReturn.getTotalReturnAmount() != null ? saleReturn.getTotalReturnAmount() : BigDecimal.ZERO);
        }

        AccountingSummaryResponse response = new AccountingSummaryResponse();
        response.setTotalIncome(totalIncome);
        response.setTotalCogs(totalCogs);
        response.setGrossProfit(grossProfit);
        response.setTotalExpenses(totalExpenses);
        response.setNetProfit(grossProfit.subtract(totalExpenses));
        List<AccountingSummaryResponse.ExpenseCategorySummary> summaries = new ArrayList<>();
        byCategory.forEach((category, amount) -> {
            AccountingSummaryResponse.ExpenseCategorySummary summary = new AccountingSummaryResponse.ExpenseCategorySummary();
            summary.setCategory(category);
            summary.setAmount(amount);
            summaries.add(summary);
        });
        response.setExpensesByCategory(summaries);
        response.setTotalInventoryLoss(totalInventoryLoss);
        response.setInventoryLossByReason(lossSummaries);
        response.setTotalRefunds(totalRefunds);
        response.setOutstandingAr(saleRepository.sumOutstandingAr());

        // Compute previous equivalent period
        long periodDays = ChronoUnit.DAYS.between(startDate, endDate);
        LocalDate previousEnd = startDate.minusDays(1);
        LocalDate previousStart = previousEnd.minusDays(periodDays);
        AccountingSummaryResponse prevResponse = computeRawSummary(previousStart, previousEnd);
        BigDecimal prevIncome = prevResponse.getTotalIncome();
        BigDecimal prevNetProfit = prevResponse.getNetProfit();

        response.setPreviousPeriodIncome(prevIncome);
        response.setPreviousPeriodProfit(prevNetProfit);

        // Guard divide-by-zero: null means "no prior data / can't compute"
        if (prevIncome != null && prevIncome.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal incomeChange = totalIncome.subtract(prevIncome)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(prevIncome.abs(), 1, RoundingMode.HALF_UP);
            response.setIncomeChangePercent(incomeChange);
        } else {
            response.setIncomeChangePercent(null);
        }

        BigDecimal currentNetProfit = response.getNetProfit();
        if (prevNetProfit != null && prevNetProfit.compareTo(BigDecimal.ZERO) != 0) {
            // Divide by |prior| so improving from a loss reads as a positive change.
            BigDecimal profitChange = currentNetProfit.subtract(prevNetProfit)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(prevNetProfit.abs(), 1, RoundingMode.HALF_UP);
            response.setProfitChangePercent(profitChange);
        } else {
            response.setProfitChangePercent(null);
        }

        return response;
    }

    /** Lightweight summary for previous-period comparison â€” no previous-period recursion. */
    private AccountingSummaryResponse computeRawSummary(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = shopTimeService.startOfDay(startDate);
        LocalDateTime endDateTime = shopTimeService.endOfDayExclusive(endDate);

        // Same tax-exclusive, net-of-returns definition as the current period so
        // income / profit change percentages compare like-for-like.
        Map<String, Object> profitSummary = getProfitSummary(startDate, endDate);
        BigDecimal totalIncome = (BigDecimal) profitSummary.get("revenue");
        BigDecimal totalCogs = (BigDecimal) profitSummary.get("cogs");

        BigDecimal totalRefunds = BigDecimal.ZERO;
        for (SaleReturn saleReturn : saleReturnRepository.findByReturnDateBetween(startDateTime, endDateTime)) {
            totalRefunds = totalRefunds.add(saleReturn.getTotalReturnAmount() != null ? saleReturn.getTotalReturnAmount() : BigDecimal.ZERO);
        }

        BigDecimal totalExpenses;
        if (ledgerReadService.isActive()) {
            // Same definition as the current period: operating expenses plus
            // inventory (write-off) losses. INVENTORY_GAIN rows are excluded so
            // the total equals the sum of its category lines and the two periods
            // are comparable.
            Map<String, BigDecimal> byCategory = ledgerReadService.expensesByCategory(startDateTime, endDateTime, false);
            totalExpenses = byCategory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, BigDecimal> inventoryLosses = ledgerReadService.expensesByCategory(startDateTime, endDateTime, true);
            for (BigDecimal loss : inventoryLosses.values()) {
                totalExpenses = totalExpenses.add(loss);
            }
        } else {
            totalExpenses = BigDecimal.ZERO;
            for (Expense expense : expenseRepository.findFiltered(null, startDate, endDate)) {
                // O4: capitalized costs are excluded from operating P&L.
                if (expense.getDeletedAt() != null || expense.isCapitalized()) continue;
                totalExpenses = totalExpenses.add(expense.getAmount());
            }
            totalExpenses = totalExpenses.add(inventoryLossService.totalLosses(startDateTime, endDateTime));
        }

        BigDecimal grossProfit = totalIncome.subtract(totalCogs);
        BigDecimal netProfit = grossProfit.subtract(totalExpenses);

        AccountingSummaryResponse r = new AccountingSummaryResponse();
        r.setTotalIncome(totalIncome);
        r.setNetProfit(netProfit);
        return r;
    }

    public List<SupplierProfitDto> getProfitBySupplier(LocalDate startDate, LocalDate endDate) {
        String sql = """
            SELECT s.id, s.name,
                   COALESCE(SUM(pi.unit_cost * pi.quantity), 0) as total_supplied_cost,
                   COALESCE((
                       SELECT SUM(si.unit_price * si.quantity)
                       FROM sale_items si
                       INNER JOIN sales sal ON si.sale_id = sal.id
                       WHERE sal.is_voided = false
                       AND sal.is_active = true
                       AND sal.deleted_at IS NULL
                       AND si.product_id IN (
                           SELECT pi2.product_id FROM purchase_items pi2
                           INNER JOIN purchases p2 ON pi2.purchase_id = p2.id
                           WHERE p2.supplier_id = s.id
                           AND p2.is_active = true
                           AND p2.deleted_at IS NULL
                           -- Only count revenue for products exclusively supplied by
                           -- this supplier, so a multi-sourced product's sale revenue
                           -- is not credited to every supplier (no double counting).
                           AND pi2.product_id NOT IN (
                               SELECT pi3.product_id FROM purchase_items pi3
                               INNER JOIN purchases p3 ON pi3.purchase_id = p3.id
                               WHERE p3.supplier_id <> s.id
                               AND p3.is_active = true
                               AND p3.deleted_at IS NULL
                           )
                       )
                       AND sal.sale_date >= ? AND sal.sale_date < ?
                   ), 0) as estimated_revenue
            FROM suppliers s
            INNER JOIN purchases p ON p.supplier_id = s.id AND p.is_active = true AND p.deleted_at IS NULL
            INNER JOIN purchase_items pi ON pi.purchase_id = p.id
            WHERE s.is_active = true AND s.deleted_at IS NULL
            AND p.purchase_date >= ? AND p.purchase_date <= ?
            GROUP BY s.id, s.name
            ORDER BY estimated_revenue DESC
        """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql,
            java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate.plusDays(1)),
            java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate));

        List<SupplierProfitDto> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            SupplierProfitDto dto = new SupplierProfitDto();
            dto.setSupplierId(((Number) row.get("id")).longValue());
            dto.setSupplierName((String) row.get("name"));
            dto.setTotalSuppliedCost(toBigDecimal(row.get("total_supplied_cost")));
            dto.setEstimatedRevenue(toBigDecimal(row.get("estimated_revenue")));

            BigDecimal profit = dto.getEstimatedRevenue().subtract(dto.getTotalSuppliedCost());
            dto.setEstimatedProfit(profit);

            BigDecimal margin = BigDecimal.ZERO;
            if (dto.getEstimatedRevenue().compareTo(BigDecimal.ZERO) > 0) {
                margin = profit.multiply(BigDecimal.valueOf(100))
                    .divide(dto.getEstimatedRevenue(), 2, java.math.RoundingMode.HALF_UP);
            }
            dto.setEstimatedMarginPercent(margin);
            result.add(dto);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Section 1: dead stock and slow-moving inventory.
    // -----------------------------------------------------------------------
    public List<DeadStockDto> getDeadStock(int daysThreshold) {
        LocalDateTime cutoffDate = shopTimeService.startOfDay(shopTimeService.today().minusDays(daysThreshold));
        List<Object[]> rows = productRepository.findDeadStock(cutoffDate);
        LocalDateTime now = shopTimeService.now();

        List<DeadStockDto> result = new ArrayList<>();
        for (Object[] row : rows) {
            Long productId = ((Number) row[0]).longValue();
            String productName = (String) row[1];
            String categoryName = (String) row[2];
            Integer stockQuantity = ((Number) row[3]).intValue();
            BigDecimal costPrice = (BigDecimal) row[4];
            LocalDateTime lastSoldDate = row[5] != null ? (LocalDateTime) row[5] : null;

            BigDecimal stockValue = costPrice != null
                    ? costPrice.multiply(BigDecimal.valueOf(stockQuantity))
                    : BigDecimal.ZERO;

            Long daysSince = lastSoldDate != null
                    ? ChronoUnit.DAYS.between(lastSoldDate.toLocalDate(), now.toLocalDate())
                    : null; // null = never sold

            result.add(new DeadStockDto(productId, productName, categoryName,
                    stockQuantity, stockValue, lastSoldDate, daysSince));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Section 2: sales timing heatmap.
    // -----------------------------------------------------------------------
    public List<SalesTimingDto> getSalesTiming(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = shopTimeService.startOfDay(startDate);
        LocalDateTime endDateTime = shopTimeService.endOfDayExclusive(endDate);

        // Key: dayOfWeek (1=Mon..7=Sun) + hourOfDay
        Map<String, SalesTimingDto> map = new LinkedHashMap<>();

        String sql = "SELECT t.sale_date, t.sale_revenue FROM (" + perSaleRevenueSql() + ") t";
        jdbcTemplate.query(sql, rs -> {
            LocalDateTime saleDate = toLocalDateTime(rs.getObject("sale_date"));
            int dayOfWeek = saleDate.getDayOfWeek().getValue(); // 1=Mon..7=Sun
            int hourOfDay = saleDate.getHour();
            String key = dayOfWeek + "_" + hourOfDay;

            BigDecimal revenue = toBigDecimal(rs.getObject("sale_revenue"));
            map.computeIfAbsent(key, k -> new SalesTimingDto(dayOfWeek, hourOfDay, 0L, BigDecimal.ZERO));
            SalesTimingDto dto = map.get(key);
            dto.setTransactionCount(dto.getTransactionCount() + 1);
            dto.setTotalRevenue(dto.getTotalRevenue().add(revenue));
        }, startDateTime, endDateTime);

        return new ArrayList<>(map.values());
    }

    // -----------------------------------------------------------------------
    // Section 4: basket analysis and frequently bought together.
    // -----------------------------------------------------------------------
    public List<BasketAffinityDto> getFrequentlyBoughtWith(Long productId, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> rows = saleItemRepository.findFrequentlyBoughtWith(productId, pageable);

        List<BasketAffinityDto> result = new ArrayList<>();
        for (Object[] row : rows) {
            Long pid = ((Number) row[0]).longValue();
            String pName = (String) row[1];
            Long count = ((Number) row[2]).longValue();
            result.add(new BasketAffinityDto(pid, pName, count));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Section 3: customer retention and lifetime value.
    // -----------------------------------------------------------------------
    public List<CustomerLtvDto> getCustomerLifetimeValue() {
        List<Object[]> rows = saleRepository.findCustomerLtvData();
        List<CustomerLtvDto> result = new ArrayList<>();

        for (Object[] row : rows) {
            CustomerLtvDto dto = new CustomerLtvDto();
            dto.setCustomerId(((Number) row[0]).longValue());
            String firstName = (String) row[1];
            String lastName = (String) row[2];
            dto.setCustomerName((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "").trim());
            dto.setPhone((String) row[3]);

            int visitCount = ((Number) row[4]).intValue();
            BigDecimal totalSpent = row[5] != null ? new BigDecimal(row[5].toString()) : BigDecimal.ZERO;
            dto.setVisitCount(visitCount);
            dto.setTotalSpent(totalSpent);
            dto.setAverageBasketSize(visitCount > 0
                    ? totalSpent.divide(BigDecimal.valueOf(visitCount), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            dto.setFirstPurchaseDate(row[6] != null ? (LocalDateTime) row[6] : null);
            dto.setLastPurchaseDate(row[7] != null ? (LocalDateTime) row[7] : null);
            result.add(dto);
        }

        return result;
    }

    public CustomerRetentionDto getCustomerRetention() {
        YearMonth thisMonth = YearMonth.now(shopTimeService.zone());
        YearMonth lastMonth = thisMonth.minusMonths(1);

        LocalDateTime lastMonthStart = shopTimeService.startOfDay(lastMonth.atDay(1));
        LocalDateTime lastMonthEnd = shopTimeService.startOfDay(thisMonth.atDay(1));
        LocalDateTime thisMonthStart = shopTimeService.startOfDay(thisMonth.atDay(1));
        LocalDateTime thisMonthEnd = shopTimeService.endOfDayExclusive(thisMonth.atEndOfMonth());

        List<Long> lastMonthIds = saleRepository.findDistinctCustomerIdsInRange(lastMonthStart, lastMonthEnd);
        List<Long> thisMonthIds = saleRepository.findDistinctCustomerIdsInRange(thisMonthStart, thisMonthEnd);

        Set<Long> lastMonthSet = new HashSet<>(lastMonthIds);
        Set<Long> thisMonthSet = new HashSet<>(thisMonthIds);

        Set<Long> lapsedSet = new HashSet<>(lastMonthSet);
        lapsedSet.removeAll(thisMonthSet);

        Set<Long> returningSet = new HashSet<>(lastMonthSet);
        returningSet.retainAll(thisMonthSet);

        // Build lapsed customer list with total historical spend â€” from LTV data
        List<CustomerLtvDto> allLtv = getCustomerLifetimeValue();
        Map<Long, CustomerLtvDto> ltvMap = new HashMap<>();
        for (CustomerLtvDto ltv : allLtv) {
            ltvMap.put(ltv.getCustomerId(), ltv);
        }

        List<CustomerRetentionDto.LapsedCustomer> lapsedList = new ArrayList<>();
        for (Long customerId : lapsedSet) {
            CustomerRetentionDto.LapsedCustomer lc = new CustomerRetentionDto.LapsedCustomer();
            lc.setCustomerId(customerId);
            CustomerLtvDto ltv = ltvMap.get(customerId);
            if (ltv != null) {
                lc.setCustomerName(ltv.getCustomerName());
                lc.setPhone(ltv.getPhone());
                lc.setLastPurchaseDate(ltv.getLastPurchaseDate());
                lc.setTotalHistoricalSpend(ltv.getTotalSpent());
            }
            lapsedList.add(lc);
        }
        // Sort by historical spend descending â€” highest-value lapsed customers first
        lapsedList.sort((a, b) -> {
            BigDecimal spendA = a.getTotalHistoricalSpend() != null ? a.getTotalHistoricalSpend() : BigDecimal.ZERO;
            BigDecimal spendB = b.getTotalHistoricalSpend() != null ? b.getTotalHistoricalSpend() : BigDecimal.ZERO;
            return spendB.compareTo(spendA);
        });

        CustomerRetentionDto dto = new CustomerRetentionDto();
        dto.setActiveLastMonth(lastMonthSet.size());
        dto.setActiveThisMonth(thisMonthSet.size());
        dto.setReturningCount(returningSet.size());
        dto.setLapsedCount(lapsedSet.size());
        dto.setLapsedCustomers(lapsedList);
        return dto;
    }

    // -----------------------------------------------------------------------
    // Inventory report
    // -----------------------------------------------------------------------
    public Map<String, Object> getInventoryReport() {
        var products = productRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByNameAsc();
        
        BigDecimal totalInventoryValue = BigDecimal.ZERO;
        int totalProducts = 0;
        int lowStockProducts = 0;
        List<Map<String, Object>> lowStockList = new ArrayList<>();

        for (var product : products) {
            totalProducts++;
            BigDecimal unitCost = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
            int stock = product.getStockQuantity() == null ? 0 : product.getStockQuantity();
            BigDecimal value = unitCost.multiply(BigDecimal.valueOf(stock));
            totalInventoryValue = totalInventoryValue.add(value);

            int minStock = product.getMinStockLevel() == null ? 0 : product.getMinStockLevel();

            if (stock <= minStock) {
                lowStockProducts++;
                Map<String, Object> item = new HashMap<>();
                item.put("productId", product.getId());
                item.put("productName", product.getName());
                item.put("currentStock", stock);
                item.put("minStockLevel", minStock);
                item.put("shortage", minStock - stock);
                lowStockList.add(item);
            }
        }

        Map<String, Object> report = new HashMap<>();
        report.put("totalProducts", totalProducts);
        report.put("totalInventoryValue", totalInventoryValue);
        report.put("lowStockProductsCount", lowStockProducts);
        report.put("lowStockItems", lowStockList);

        return report;
    }

    // -----------------------------------------------------------------------
    // Employee shift / cash drawer / end-of-day reports
    // -----------------------------------------------------------------------

    /**
     * Per-employee-shift report. Cash and non-cash shifts are returned side by
     * side; only cash shifts carry a drawer reference. The drawer's expected
     * cash and variance are intentionally not copied onto every employee.
     */
    public Map<String, Object> getEmployeeShiftReport(LocalDate startDate, LocalDate endDate,
                                                      Long cashierId, String shiftType) {
        LocalDate from = startDate != null ? startDate : shopTimeService.today();
        LocalDate to = endDate != null ? endDate : from;
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }

        List<CashShiftResponse> shifts = cashShiftService.getShiftReport(
                shopTimeService.startOfDay(from), shopTimeService.endOfDayInclusive(to), cashierId, shiftType);

        BigDecimal grossSales = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        BigDecimal netSales = BigDecimal.ZERO;
        BigDecimal cashSales = BigDecimal.ZERO;
        BigDecimal nonCashSales = BigDecimal.ZERO;
        long totalTransactions = 0;
        long totalVoided = 0;
        long openShifts = 0;
        Map<String, BigDecimal> paymentBreakdown = new LinkedHashMap<>();

        for (CashShiftResponse shift : shifts) {
            grossSales = grossSales.add(toBigDecimal(shift.getGrossSales()));
            discounts = discounts.add(toBigDecimal(shift.getDiscountTotal()));
            refunds = refunds.add(toBigDecimal(shift.getReturnsTotal()));
            netSales = netSales.add(toBigDecimal(shift.getNetSales()));
            cashSales = cashSales.add(toBigDecimal(shift.getCashSalesTotal()));
            nonCashSales = nonCashSales.add(toBigDecimal(shift.getNonCashSalesTotal()));
            totalTransactions += shift.getTransactionCount();
            totalVoided += shift.getVoidedCount();
            if ("OPEN".equals(shift.getStatus())) {
                openShifts++;
            }
            if (shift.getPaymentBreakdown() != null) {
                shift.getPaymentBreakdown().forEach((method, amount) ->
                        paymentBreakdown.merge(method, toBigDecimal(amount), BigDecimal::add));
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startDate", from);
        report.put("endDate", to);
        report.put("totalShifts", shifts.size());
        report.put("openShifts", openShifts);
        report.put("transactionCount", totalTransactions);
        report.put("voidedCount", totalVoided);
        report.put("grossSales", grossSales);
        report.put("discounts", discounts);
        report.put("refunds", refunds);
        report.put("netSales", netSales);
        report.put("cashSales", cashSales);
        report.put("nonCashSales", nonCashSales);
        report.put("paymentBreakdown", paymentBreakdown);
        report.put("shifts", shifts);
        return report;
    }

    /**
     * Physical drawer report. Expected cash comes from the signed movement
     * ledger, so it always includes every employee who sold through the drawer
     * and excludes wallet, card and bank revenue.
     */
    public Map<String, Object> getDrawerSessionReport(LocalDate startDate, LocalDate endDate) {
        LocalDate from = startDate != null ? startDate : shopTimeService.today();
        LocalDate to = endDate != null ? endDate : from;
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }

        List<CashDrawerSession> sessions = cashDrawerSessionRepository.findForReport(
                shopTimeService.startOfDay(from), shopTimeService.endOfDayInclusive(to));
        List<DrawerSessionResponse> rows = sessions.stream()
                .map(session -> cashDrawerSessionService.toResponse(session, false, true))
                .toList();

        BigDecimal openingFloat = BigDecimal.ZERO;
        BigDecimal cashSales = BigDecimal.ZERO;
        BigDecimal cashRefunds = BigDecimal.ZERO;
        BigDecimal safeDrops = BigDecimal.ZERO;
        BigDecimal payIns = BigDecimal.ZERO;
        BigDecimal payOuts = BigDecimal.ZERO;
        BigDecimal adjustments = BigDecimal.ZERO;
        BigDecimal expectedCash = BigDecimal.ZERO;
        BigDecimal countedCash = BigDecimal.ZERO;
        BigDecimal variance = BigDecimal.ZERO;
        long openSessions = 0;

        for (DrawerSessionResponse row : rows) {
            openingFloat = openingFloat.add(toBigDecimal(row.getOpeningFloat()));
            cashSales = cashSales.add(toBigDecimal(row.getCashSales()));
            cashRefunds = cashRefunds.add(toBigDecimal(row.getCashRefunds()));
            safeDrops = safeDrops.add(toBigDecimal(row.getSafeDrops()));
            payIns = payIns.add(toBigDecimal(row.getPayIns()));
            payOuts = payOuts.add(toBigDecimal(row.getPayOuts()));
            adjustments = adjustments.add(toBigDecimal(row.getAdjustments()));
            expectedCash = expectedCash.add(toBigDecimal(row.getExpectedCash()));
            countedCash = countedCash.add(toBigDecimal(row.getCountedCash()));
            variance = variance.add(toBigDecimal(row.getVariance()));
            if ("OPEN".equals(row.getStatus())) {
                openSessions++;
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startDate", from);
        report.put("endDate", to);
        report.put("totalSessions", rows.size());
        report.put("openSessions", openSessions);
        report.put("openingFloat", openingFloat);
        report.put("cashSales", cashSales);
        report.put("cashRefunds", cashRefunds);
        report.put("safeDrops", safeDrops);
        report.put("payIns", payIns);
        report.put("payOuts", payOuts);
        report.put("adjustments", adjustments);
        report.put("expectedCash", expectedCash);
        report.put("countedCash", countedCash);
        report.put("variance", variance);
        report.put("sessions", rows);
        return report;
    }

    /**
     * End-of-day report. The five sections are deliberately separate so digital
     * revenue can never be mistaken for cash that should be in the drawer.
     */
    public Map<String, Object> getEndOfDayReport(LocalDate date) {
        LocalDate day = date != null ? date : shopTimeService.today();
        LocalDateTime start = shopTimeService.startOfDay(day);
        LocalDateTime end = shopTimeService.endOfDayInclusive(day);

        Page<Sale> salesPage = saleRepository.findByDateRange(start, end,
                PageRequest.of(0, REPORT_MAX_ROWS));
        List<Sale> sales = salesPage.getContent();
        List<Long> saleIds = sales.stream()
                .map(Sale::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, List<SalePayment>> paymentsBySale = saleIds.isEmpty()
                ? Collections.emptyMap()
                : salePaymentRepository.findBySaleIdInOrderByPaidAtAsc(saleIds).stream()
                        .collect(Collectors.groupingBy(payment -> payment.getSale().getId()));

        BigDecimal grossSales = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        BigDecimal netSales = BigDecimal.ZERO;
        BigDecimal cashSales = BigDecimal.ZERO;
        BigDecimal nonCashSales = BigDecimal.ZERO;
        BigDecimal creditSales = BigDecimal.ZERO;
        long transactionCount = 0;
        long voidedCount = 0;
        Map<String, BigDecimal> methodTotals = new LinkedHashMap<>();
        Map<String, Long> methodCounts = new LinkedHashMap<>();

        for (Sale sale : sales) {
            if (Boolean.TRUE.equals(sale.getIsVoided())) {
                voidedCount++;
                continue;
            }
            transactionCount++;
            grossSales = grossSales.add(toBigDecimal(sale.getSubtotal()));
            discounts = discounts
                    .add(toBigDecimal(sale.getDiscountAmount()))
                    .add(toBigDecimal(sale.getPromotionDiscount()))
                    .add(toBigDecimal(sale.getPriceListDiscount()));
            BigDecimal total = toBigDecimal(sale.getTotalAmount());
            netSales = netSales.add(total);

            List<SalePayment> lines = paymentsBySale.get(sale.getId());
            if (lines == null || lines.isEmpty()) {
                // Historical sale created before split payments existed.
                String method = sale.getPaymentMethod() != null
                        ? sale.getPaymentMethod().name() : "UNKNOWN";
                methodTotals.merge(method, total, BigDecimal::add);
                methodCounts.merge(method, 1L, Long::sum);
                if ("CASH".equals(method)) {
                    cashSales = cashSales.add(total);
                } else {
                    nonCashSales = nonCashSales.add(total);
                    if ("CREDIT".equals(method)) {
                        creditSales = creditSales.add(total);
                    }
                }
            } else {
                Set<String> countedMethods = new HashSet<>();
                for (SalePayment line : lines) {
                    String method = line.getPaymentMethod() != null
                            ? line.getPaymentMethod().name() : "UNKNOWN";
                    BigDecimal amount = toBigDecimal(line.getAmount());
                    methodTotals.merge(method, amount, BigDecimal::add);
                    if (countedMethods.add(method)) {
                        methodCounts.merge(method, 1L, Long::sum);
                    }
                    if ("CASH".equals(method)) {
                        cashSales = cashSales.add(amount);
                    } else {
                        nonCashSales = nonCashSales.add(amount);
                        if ("CREDIT".equals(method)) {
                            creditSales = creditSales.add(amount);
                        }
                    }
                }
            }
        }

        List<SaleReturn> returns = saleReturnRepository.findByReturnDateBetween(start, end);
        BigDecimal refundTotal = BigDecimal.ZERO;
        Map<String, BigDecimal> refundsByMethod = new LinkedHashMap<>();
        for (SaleReturn saleReturn : returns) {
            BigDecimal amount = toBigDecimal(saleReturn.getTotalReturnAmount());
            refundTotal = refundTotal.add(amount);
            refundsByMethod.merge(saleReturn.getRefundMethod().name(), amount, BigDecimal::add);
        }

        Map<String, Object> business = new LinkedHashMap<>();
        business.put("transactionCount", transactionCount);
        business.put("voidedCount", voidedCount);
        business.put("grossSales", grossSales);
        business.put("discounts", discounts);
        business.put("refunds", refundTotal);
        business.put("netSales", netSales);
        business.put("averageSale", transactionCount == 0 ? BigDecimal.ZERO
                : netSales.divide(BigDecimal.valueOf(transactionCount), 2, RoundingMode.HALF_UP));
        business.put("cashSales", cashSales);
        business.put("digitalSales", nonCashSales.subtract(creditSales));
        business.put("creditSales", creditSales);
        business.put("nonCashSales", nonCashSales);
        business.put("refundsByMethod", refundsByMethod);

        List<Map<String, Object>> paymentMethods = new ArrayList<>();
        methodTotals.forEach((method, amount) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", method);
            row.put("amount", amount);
            row.put("transactionCount", methodCounts.getOrDefault(method, 0L));
            paymentMethods.add(row);
        });

        Map<String, Object> drawerReport = getDrawerSessionReport(day, day);
        Map<String, Object> shiftReport = getEmployeeShiftReport(day, day, null, null);

        @SuppressWarnings("unchecked")
        List<DrawerSessionResponse> drawerRows =
                (List<DrawerSessionResponse>) drawerReport.get("sessions");
        @SuppressWarnings("unchecked")
        List<CashShiftResponse> shiftRows =
                (List<CashShiftResponse>) shiftReport.get("shifts");

        List<Map<String, Object>> exceptions = new ArrayList<>();
        for (CashShiftResponse shift : shiftRows) {
            if ("OPEN".equals(shift.getStatus())) {
                exceptions.add(exception("OPEN_SHIFT", "WARNING",
                        "Employee shift #" + shift.getId() + " (" + shift.getCashierName()
                                + ") is still open.",
                        "CashShift", shift.getId()));
            }
            if (Boolean.TRUE.equals(shift.getForceClosed())) {
                exceptions.add(exception("FORCE_CLOSED_SHIFT", "WARNING",
                        "Employee shift #" + shift.getId() + " was force-closed: "
                                + shift.getForceCloseReason(),
                        "CashShift", shift.getId()));
            }
            if (shift.getVariance() != null
                    && CashVarianceUtil.exceedsTolerance(shift.getVariance(),
                    systemSettingService.cashVarianceTolerance())) {
                exceptions.add(exception("CASH_VARIANCE", "WARNING",
                        "Employee shift #" + shift.getId() + " has a cash variance of "
                                + shift.getVariance() + ".",
                        "CashShift", shift.getId()));
            }
            if (!"CASH_DRAWER".equals(shift.getShiftType())
                    && toBigDecimal(shift.getCashSalesTotal()).compareTo(BigDecimal.ZERO) > 0) {
                exceptions.add(exception("CASH_ON_NON_CASH_SHIFT", "CRITICAL",
                        "Non-cash shift #" + shift.getId() + " contains cash sales.",
                        "CashShift", shift.getId()));
            }
            if ("CASH_DRAWER".equals(shift.getShiftType()) && shift.getDrawerSessionId() == null) {
                exceptions.add(exception("SHIFT_WITHOUT_DRAWER", "WARNING",
                        "Cash shift #" + shift.getId() + " has no drawer session.",
                        "CashShift", shift.getId()));
            }
        }

        for (DrawerSessionResponse drawer : drawerRows) {
            if ("OPEN".equals(drawer.getStatus())) {
                exceptions.add(exception("OPEN_DRAWER", "WARNING",
                        "Drawer " + drawer.getDrawerName() + " (session #" + drawer.getId()
                                + ") is still open.",
                        "CashDrawerSession", drawer.getId()));
            }
            if (drawer.getVariance() != null
                    && CashVarianceUtil.exceedsTolerance(drawer.getVariance(),
                    systemSettingService.cashVarianceTolerance())) {
                exceptions.add(exception("DRAWER_VARIANCE", "WARNING",
                        "Drawer session #" + drawer.getId() + " variance is "
                                + drawer.getVariance() + ".",
                        "CashDrawerSession", drawer.getId()));
            }
            if (Boolean.TRUE.equals(drawer.getForceClosed())) {
                exceptions.add(exception("FORCE_CLOSED_DRAWER", "WARNING",
                        "Drawer session #" + drawer.getId() + " was force-closed: "
                                + drawer.getForceCloseReason(),
                        "CashDrawerSession", drawer.getId()));
            }
            if (drawer.getExpectedCash() != null
                    && drawer.getExpectedCash().compareTo(BigDecimal.ZERO) < 0) {
                exceptions.add(exception("NEGATIVE_EXPECTED_CASH", "CRITICAL",
                        "Drawer session #" + drawer.getId() + " has negative expected cash ("
                                + drawer.getExpectedCash() + ").",
                        "CashDrawerSession", drawer.getId()));
            }
        }

        for (Sale sale : sales) {
            if (Boolean.TRUE.equals(sale.getIsVoided())) {
                continue;
            }
            List<SalePayment> lines = paymentsBySale.get(sale.getId());
            boolean cashSale = lines != null && !lines.isEmpty()
                    ? lines.stream().anyMatch(line -> line.getPaymentMethod() == PaymentMethod.CASH)
                    : sale.getPaymentMethod() == Sale.PaymentMethod.CASH;
            if (sale.getEmployeeShift() == null) {
                exceptions.add(exception("SALE_WITHOUT_SHIFT", "WARNING",
                        "Sale " + sale.getInvoiceNumber() + " has no employee shift.",
                        "Sale", sale.getId()));
            } else if (cashSale && sale.getEmployeeShift().getDrawerSession() == null) {
                exceptions.add(exception("CASH_SALE_WITHOUT_DRAWER", "CRITICAL",
                        "Cash sale " + sale.getInvoiceNumber() + " is not linked to a drawer session.",
                        "Sale", sale.getId()));
            }
        }
        if (voidedCount > 0) {
            exceptions.add(exception("VOIDED_SALES", "INFO",
                    voidedCount + " sale(s) were voided on " + day + ".",
                    "Sale", null));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("date", day);
        report.put("generatedAt", shopTimeService.now());
        // P7: this report still loads sale entities for the exception checks;
        // if the cap ever bites, say so instead of truncating silently.
        report.put("truncated", salesPage.getTotalElements() > REPORT_MAX_ROWS);
        report.put("rowCount", sales.size());
        report.put("businessSummary", business);
        report.put("paymentMethods", paymentMethods);
        report.put("cashDrawer", drawerReport);
        report.put("employeeShifts", shiftRows);
        report.put("employeeShiftSummary", shiftReport);
        report.put("exceptions", exceptions);
        return report;
    }

    private Map<String, Object> exception(String type, String severity, String message,
                                          String entityType, Long entityId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("severity", severity);
        row.put("message", message);
        row.put("entityType", entityType);
        row.put("entityId", entityId);
        return row;
    }

    /**
     * sqlite-jdbc returns SUM()/aggregate columns whose inputs are all integer
     * as Integer/Long (no DECIMAL affinity), whereas MySQL returned BigDecimal.
     * Convert whatever JDBC hands back to a precise BigDecimal.
     */
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(value.toString());
    }

    /**
     * P7: per-line revenue/COGS aggregation in SQL, mirroring
     * SaleDiscountUtils.allocateDiscounts + netItemRevenue and
     * CostCalculationUtils.calculateCogs exactly (proportional discount
     * shares rounded HALF_UP to 2dp, remainder on the last positive line,
     * revenue prorated over non-refunded units). Two ? params: start, end
     * (half-open). Window functions keep this one pass over the lines.
     */
    private String lineRevenueSql() {
        return """
            SELECT z.*,
                CASE WHEN z.qty_sold > 0 AND z.qty > 0
                     THEN ROUND((CASE WHEN z.gross_line - z.alloc_share < 0 THEN 0
                                      ELSE z.gross_line - z.alloc_share END)
                          * z.qty_sold / CAST(z.qty AS DECIMAL(19,6)), 2)
                     ELSE 0 END AS line_revenue,
                CASE WHEN z.qty_sold > 0
                     THEN ROUND(z.cost_at_sale * z.qty_sold, 6) ELSE 0 END AS line_cogs,
                CASE WHEN z.qty > 0 AND z.qty_refunded > 0
                     THEN ROUND((CASE WHEN z.gross_line - z.alloc_share < 0 THEN 0
                                      ELSE z.gross_line - z.alloc_share END)
                          * (CASE WHEN z.qty_refunded > z.qty THEN z.qty ELSE z.qty_refunded END)
                          / CAST(z.qty AS DECIMAL(19,6)), 2)
                     ELSE 0 END AS refunded_revenue_line,
                CASE WHEN z.qty <= 0 OR z.qty_sold >= z.qty THEN z.line_tax
                     ELSE ROUND(z.line_tax * z.qty_sold / CAST(z.qty AS DECIMAL(19,6)), 2) END AS prorated_tax
            FROM (
                SELECT x.*,
                    CASE
                        WHEN x.eff_discount <= 0 OR x.gross_total <= 0 THEN 0
                        WHEN x.gross_line <= 0 THEN 0
                        WHEN x.item_id = x.remainder_item_id THEN
                            CASE WHEN x.eff_discount - x.other_shares < 0 THEN 0
                                 ELSE x.eff_discount - x.other_shares END
                        ELSE ROUND(x.eff_discount * x.gross_line / CAST(x.gross_total AS DECIMAL(19,6)), 2)
                    END AS alloc_share
                FROM (
                    SELECT b.*,
                        SUM(CASE WHEN b.item_id <> b.remainder_item_id AND b.gross_line > 0 AND b.eff_discount > 0
                                 THEN ROUND(b.eff_discount * b.gross_line / CAST(b.gross_total AS DECIMAL(19,6)), 2)
                                 ELSE 0 END) OVER (PARTITION BY b.sale_id) AS other_shares
                    FROM (
                        SELECT a.*,
                            CASE WHEN a.sale_discount > 0 AND a.gross_total > 0
                                 THEN CASE WHEN a.sale_discount > a.gross_total THEN a.gross_total
                                           ELSE a.sale_discount END
                                 ELSE 0 END AS eff_discount
                        FROM (
                            SELECT si.id AS item_id, si.sale_id AS sale_id, s.sale_date AS sale_date,
                                s.cashier_id AS cashier_id, si.product_id AS product_id,
                                COALESCE(si.quantity, 0) AS qty,
                                CASE WHEN COALESCE(si.quantity, 0) - COALESCE(si.quantity_refunded, 0) < 0 THEN 0
                                     ELSE COALESCE(si.quantity, 0) - COALESCE(si.quantity_refunded, 0) END AS qty_sold,
                                COALESCE(si.quantity_refunded, 0) AS qty_refunded,
                                ROUND(COALESCE(si.total_price, COALESCE(si.unit_price, 0) * COALESCE(si.quantity, 0)), 2) AS gross_line,
                                COALESCE(si.cost_price_at_sale, 0) AS cost_at_sale,
                                COALESCE(si.tax_amount, 0) AS line_tax,
                                COALESCE(s.discount_amount, 0) AS sale_discount,
                                SUM(ROUND(COALESCE(si.total_price, COALESCE(si.unit_price, 0) * COALESCE(si.quantity, 0)), 2))
                                    OVER (PARTITION BY si.sale_id) AS gross_total,
                                MAX(CASE WHEN ROUND(COALESCE(si.total_price, COALESCE(si.unit_price, 0) * COALESCE(si.quantity, 0)), 2) > 0
                                         THEN si.id END) OVER (PARTITION BY si.sale_id) AS remainder_item_id
                            FROM sale_items si
                            JOIN sales s ON s.id = si.sale_id
                            WHERE s.is_active = true AND s.deleted_at IS NULL AND s.is_voided = false
                              AND s.sale_date >= ? AND s.sale_date < ?
                        ) a
                    ) b
                ) x
            ) z
            """;
    }

    /**
     * Per-sale netSaleRevenue in SQL: gross minus the capped sale discount
     * minus the discounted value of already-refunded units, floored at zero.
     * One row per sale; two ? params (start, end) inside lineRevenueSql().
     */
    private String perSaleRevenueSql() {
        return "SELECT t.sale_id, t.sale_date, t.cashier_id, "
                + "CASE WHEN MAX(t.gross_total) - MAX(t.eff_discount) - SUM(t.refunded_revenue_line) > 0 "
                + "THEN MAX(t.gross_total) - MAX(t.eff_discount) - SUM(t.refunded_revenue_line) "
                + "ELSE 0 END AS sale_revenue, "
                + "SUM(t.qty_sold) AS items_sold "
                + "FROM (" + lineRevenueSql() + ") t "
                + "GROUP BY t.sale_id, t.sale_date, t.cashier_id";
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toLocalDateTime();
        }
        if (value instanceof String text) {
            return LocalDateTime.parse(text.trim().replace(' ', 'T'));
        }
        throw new IllegalStateException("Unexpected timestamp value: " + value);
    }

    private LocalDate resolveStartDate(String period, LocalDate endDate) {
        return switch (period != null ? period.toUpperCase(Locale.ROOT) : "MONTH") {
            case "WEEK" -> endDate.minusWeeks(1);
            case "YEAR" -> endDate.minusYears(1);
            default -> endDate.minusMonths(1);
        };
    }

    private LocalDate[] resolveComparisonRange(String period, String compareMode, LocalDate startDate, LocalDate endDate) {
        if ("YEAR_AGO".equals(compareMode)) {
            return new LocalDate[]{ startDate.minusYears(1), endDate.minusYears(1) };
        }
        // Default: PREVIOUS_PERIOD â€” same length, immediately before the current range
        long daysInPeriod = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        return new LocalDate[]{ startDate.minusDays(daysInPeriod), startDate.minusDays(1) };
    }

    private Map<Long, BigDecimal> computeProductRevenue(LocalDate startDate, LocalDate endDate) {
        String sql = "SELECT l.product_id, SUM(l.line_revenue) AS revenue FROM ("
                + lineRevenueSql() + ") l WHERE l.qty_sold > 0 GROUP BY l.product_id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql,
                shopTimeService.startOfDay(startDate), shopTimeService.endOfDayExclusive(endDate));
        Map<Long, BigDecimal> revenueByProduct = new HashMap<>();
        for (Map<String, Object> row : rows) {
            revenueByProduct.put(((Number) row.get("product_id")).longValue(),
                    toBigDecimal(row.get("revenue")));
        }
        return revenueByProduct;
    }

    private Map<Long, BigDecimal> computeCategoryRevenue(LocalDate startDate, LocalDate endDate) {
        String sql = "SELECT COALESCE(c.id, -1) AS category_id, SUM(l.line_revenue) AS revenue FROM ("
                + lineRevenueSql() + ") l "
                + "LEFT JOIN products p ON p.id = l.product_id "
                + "LEFT JOIN categories c ON c.id = p.category_id "
                + "WHERE l.qty_sold > 0 GROUP BY COALESCE(c.id, -1)";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql,
                shopTimeService.startOfDay(startDate), shopTimeService.endOfDayExclusive(endDate));
        Map<Long, BigDecimal> revenueByCategory = new HashMap<>();
        for (Map<String, Object> row : rows) {
            revenueByCategory.put(((Number) row.get("category_id")).longValue(),
                    toBigDecimal(row.get("revenue")));
        }
        return revenueByCategory;
    }
}
