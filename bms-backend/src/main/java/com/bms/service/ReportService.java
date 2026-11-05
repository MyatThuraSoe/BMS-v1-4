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
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ReportService {

    /**
     * Upper bound for in-memory report queries. Reports load full result sets
     * (not paged for the UI), so this guards memory while still comfortably
     * covering real-world monthly volumes without silently dropping rows.
     */
    private static final int REPORT_MAX_ROWS = 50_000;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

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

    // Daily sales report
    public Map<String, Object> getDailySalesReport(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, REPORT_MAX_ROWS);
        Page<Sale> salesPage = saleRepository.findByDateRange(startOfDay, endOfDay, pageable);
        List<Sale> sales = salesPage.getContent();

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        int totalTransactions = 0;
        long totalItemsSold = 0;
        Map<Long, BigDecimal> productRevenue = new HashMap<>();
        Map<Long, Integer> productQuantities = new HashMap<>();
        Map<Long, BigDecimal> productTax = new HashMap<>();
        Map<Long, BigDecimal> productCost = new HashMap<>();
        Map<Long, Product> productCache = new HashMap<>();

        for (Sale sale : sales) {
            if (Boolean.TRUE.equals(sale.getIsVoided())) {
                continue;
            }
            totalTransactions++;
            var discountAllocations = SaleDiscountUtils.allocateDiscounts(sale);

            for (var item : sale.getItems()) {
                int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                if (quantitySold <= 0) {
                    continue;
                }
                Product product = item.getProduct();
                productCache.putIfAbsent(product.getId(), product);
                productQuantities.merge(product.getId(), quantitySold, Integer::sum);
                BigDecimal revenue = SaleDiscountUtils.netItemRevenue(
                        item, discountAllocations.getOrDefault(item, BigDecimal.ZERO), quantitySold);
                totalRevenue = totalRevenue.add(revenue);
                productRevenue.merge(product.getId(), revenue, BigDecimal::add);
                BigDecimal tax = prorateTax(item, quantitySold);
                productTax.merge(product.getId(), tax, BigDecimal::add);
                if (item.getCostPriceAtSale() != null) {
                    BigDecimal cost = item.getCostPriceAtSale().multiply(BigDecimal.valueOf(quantitySold));
                    productCost.merge(product.getId(), cost, BigDecimal::add);
                    totalCost = totalCost.add(cost);
                }
                totalItemsSold += quantitySold;
            }
        }

        List<Map<String, Object>> itemSales = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : productQuantities.entrySet()) {
            Long productId = entry.getKey();
            int quantitySold = entry.getValue();
            BigDecimal revenue = productRevenue.getOrDefault(productId, BigDecimal.ZERO);
            BigDecimal cost = productCost.getOrDefault(productId, BigDecimal.ZERO);
            BigDecimal unitPrice = quantitySold > 0
                    ? revenue.divide(BigDecimal.valueOf(quantitySold), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            Product product = productCache.get(productId);
            Map<String, Object> row = new HashMap<>();
            row.put("productId", productId);
            row.put("productName", product != null ? product.getName() : "");
            row.put("sku", product != null ? product.getSku() : "");
            row.put("quantitySold", quantitySold);
            row.put("unitPrice", unitPrice);
            row.put("taxAmount", productTax.getOrDefault(productId, BigDecimal.ZERO));
            row.put("totalPrice", revenue);
            row.put("costAmount", cost);
            row.put("profit", revenue.subtract(cost));
            itemSales.add(row);
        }
        itemSales.sort(Comparator.comparing((Map<String, Object> r) ->
                ((Number) r.get("totalPrice")).longValue()).reversed());

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
        
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, REPORT_MAX_ROWS);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        BigDecimal totalRevenue = BigDecimal.ZERO;
        int totalTransactions = 0;

        for (Sale sale : sales) {
            if (!Boolean.TRUE.equals(sale.getIsVoided())) {
                totalRevenue = totalRevenue.add(calculateNetSaleRevenue(sale));
                totalTransactions++;
            }
        }

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
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, REPORT_MAX_ROWS);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        Map<Long, Map<String, Object>> productStats = new HashMap<>();

        for (Sale sale : sales) {
            if (!Boolean.TRUE.equals(sale.getIsVoided())) {
                var discountAllocations = SaleDiscountUtils.allocateDiscounts(sale);
                for (var item : sale.getItems()) {
                    int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                    if (quantitySold <= 0) {
                        continue;
                    }
                    Long productId = item.getProduct().getId();
                    
                    productStats.computeIfAbsent(productId, k -> {
                        Map<String, Object> stats = new HashMap<>();
                        stats.put("productId", productId);
                        stats.put("productName", item.getProduct().getName());
                        stats.put("totalQuantitySold", 0);
                        stats.put("totalRevenue", BigDecimal.ZERO);
                        return stats;
                    });
                    
                    Map<String, Object> stats = productStats.get(productId);
                    stats.put("totalQuantitySold", (Integer) stats.get("totalQuantitySold") + quantitySold);
                    stats.put("totalRevenue", ((BigDecimal) stats.get("totalRevenue")).add(
                            SaleDiscountUtils.netItemRevenue(item,
                                    discountAllocations.getOrDefault(item, BigDecimal.ZERO),
                                    quantitySold)));
                }
            }
        }

        List<Map<String, Object>> report = new ArrayList<>(productStats.values());
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
    // Â§6 Cashier performance â€” fixed: accumulate ALL items/sales first, then compute averages
    // -----------------------------------------------------------------------
    public List<Map<String, Object>> getCashierPerformanceReport(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, REPORT_MAX_ROWS);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        Map<Long, Map<String, Object>> cashierStats = new HashMap<>();

        for (Sale sale : sales) {
            if (!Boolean.TRUE.equals(sale.getIsVoided())) {
                Long cashierId = sale.getCashierId();

                cashierStats.computeIfAbsent(cashierId, k -> {
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("cashierId", cashierId);
                    stats.put("totalSales", BigDecimal.ZERO);
                    stats.put("transactionCount", 0);
                    stats.put("totalItems", 0);
                    return stats;
                });

                Map<String, Object> stats = cashierStats.get(cashierId);
                // Accumulate totals first â€” averages computed after the loop
                stats.put("totalSales", ((BigDecimal) stats.get("totalSales")).add(calculateNetSaleRevenue(sale)));
                stats.put("transactionCount", (Integer) stats.get("transactionCount") + 1);

                int itemCount = sale.getItems().stream()
                        .mapToInt(item -> effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded()))
                        .sum();
                stats.put("totalItems", (Integer) stats.get("totalItems") + itemCount);
            }
        }

        // Compute fairness-adjusted averages after the loop (so denominator is the full count)
        for (Map<String, Object> stats : cashierStats.values()) {
            int txCount = (Integer) stats.get("transactionCount");
            if (txCount > 0) {
                stats.put("averageTransactionValue",
                        ((BigDecimal) stats.get("totalSales")).divide(BigDecimal.valueOf(txCount), 2, RoundingMode.HALF_UP));
                stats.put("averageItemsPerSale",
                        (double) (Integer) stats.get("totalItems") / txCount);
            } else {
                stats.put("averageTransactionValue", BigDecimal.ZERO);
                stats.put("averageItemsPerSale", 0.0);
            }
        }

        List<Map<String, Object>> report = new ArrayList<>(cashierStats.values());
        report.sort((a, b) -> ((BigDecimal) b.get("totalSales")).compareTo((BigDecimal) a.get("totalSales")));
        
        return report;
    }

    public List<DailySalesTrendDto> getSalesTrend(int days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(Math.max(days, 1) - 1);
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, REPORT_MAX_ROWS);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();

        Map<LocalDate, DailySalesTrendDto> trendByDate = new LinkedHashMap<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            trendByDate.put(date, new DailySalesTrendDto(date, BigDecimal.ZERO, 0));
        }

        for (Sale sale : sales) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) {
                continue;
            }
            LocalDate saleDate = sale.getSaleDate().toLocalDate();
            if (!trendByDate.containsKey(saleDate)) {
                continue;
            }
            DailySalesTrendDto dto = trendByDate.get(saleDate);
            dto.setTotalSales(dto.getTotalSales().add(calculateNetSaleRevenue(sale)));
            dto.setTransactionCount(dto.getTransactionCount() + 1);
        }

        return new ArrayList<>(trendByDate.values());
    }

    public List<Map<String, Object>> getTopProducts(String period, int limit, String compareMode) {
        LocalDate endDate = LocalDate.now();
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
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = resolveStartDate(period, endDate);
        LocalDate[] compareRange = resolveComparisonRange(period, compareMode, startDate, endDate);

        // 1. Get current period data (and capture category names)
        Map<Long, String> categoryNames = new HashMap<>();
        Map<Long, BigDecimal> currentRevenueMap = new HashMap<>();
        
        List<Sale> currentSales = saleRepository.findByDateRange(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay(), PageRequest.of(0, REPORT_MAX_ROWS)).getContent();
        for (Sale sale : currentSales) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) continue;
            var discountAllocations = SaleDiscountUtils.allocateDiscounts(sale);
            for (var item : sale.getItems()) {
                int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                if (quantitySold <= 0) continue;
                Long categoryId = item.getProduct().getCategory() != null ? item.getProduct().getCategory().getId() : -1L;
                String categoryName = item.getProduct().getCategory() != null ? item.getProduct().getCategory().getName() : "Uncategorized";
                
                categoryNames.put(categoryId, categoryName);
                BigDecimal itemRevenue = SaleDiscountUtils.netItemRevenue(
                        item, discountAllocations.getOrDefault(item, BigDecimal.ZERO), quantitySold);
                currentRevenueMap.merge(categoryId, itemRevenue, BigDecimal::add);
            }
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
        Set<Long> wanted = new HashSet<>(categoryIds);
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = resolveStartDate(period, endDate);

        List<Sale> sales = saleRepository.findByDateRange(
                startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay(),
                PageRequest.of(0, REPORT_MAX_ROWS)).getContent();

        // day -> (categoryId -> revenue)
        Map<LocalDate, Map<Long, BigDecimal>> daily = new TreeMap<>();
        for (Sale sale : sales) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) continue;
            LocalDate day = sale.getSaleDate().toLocalDate();
            var discountAllocations = SaleDiscountUtils.allocateDiscounts(sale);
            for (var item : sale.getItems()) {
                int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                if (quantitySold <= 0) continue;
                Long categoryId = item.getProduct().getCategory() != null ? item.getProduct().getCategory().getId() : -1L;
                if (!wanted.contains(categoryId)) continue;
                BigDecimal itemRevenue = SaleDiscountUtils.netItemRevenue(
                        item, discountAllocations.getOrDefault(item, BigDecimal.ZERO), quantitySold);
                daily.computeIfAbsent(day, k -> new HashMap<>())
                        .merge(categoryId, itemRevenue, BigDecimal::add);
            }
        }

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
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();
        List<Sale> sales = saleRepository.findByDateRange(startDateTime, endDateTime, PageRequest.of(0, REPORT_MAX_ROWS)).getContent();

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        for (Sale sale : sales) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) {
                continue;
            }
            var discountAllocations = SaleDiscountUtils.allocateDiscounts(sale);
            for (var item : sale.getItems()) {
                int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                if (quantitySold <= 0) {
                    continue;
                }
                revenue = revenue.add(SaleDiscountUtils.netItemRevenue(
                        item, discountAllocations.getOrDefault(item, BigDecimal.ZERO), quantitySold));
                cogs = cogs.add(CostCalculationUtils.calculateCogs(item.getCostPriceAtSale(), quantitySold));
            }
        }

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
        LocalDate today = LocalDate.now();
        String normalizedPeriod = period != null ? period.toUpperCase(Locale.ROOT) : "MONTH";
        int safePoints = Math.max(points, 1);
        List<Map<String, Object>> trend = new ArrayList<>();

        for (int i = safePoints - 1; i >= 0; i--) {
            LocalDate bucketStart;
            LocalDate bucketEnd;
            String label;

            if ("WEEK".equals(normalizedPeriod)) {
                bucketEnd = today.minusWeeks(i);
                bucketStart = bucketEnd.minusDays(6);
                label = bucketStart + " - " + bucketEnd;
            } else if ("YEAR".equals(normalizedPeriod)) {
                int year = today.getYear() - i;
                bucketStart = LocalDate.of(year, 1, 1);
                bucketEnd = LocalDate.of(year, 12, 31);
                label = String.valueOf(year);
            } else {
                YearMonth yearMonth = YearMonth.from(today).minusMonths(i);
                bucketStart = yearMonth.atDay(1);
                bucketEnd = yearMonth.atEndOfMonth();
                label = yearMonth.toString();
            }

            LocalDateTime startDateTime = bucketStart.atStartOfDay();
            LocalDateTime endDateTime = bucketEnd.plusDays(1).atStartOfDay();
            List<Sale> sales = saleRepository.findByDateRange(startDateTime, endDateTime, PageRequest.of(0, REPORT_MAX_ROWS)).getContent();
            BigDecimal revenue = BigDecimal.ZERO;
            BigDecimal cogs = BigDecimal.ZERO;
            for (Sale sale : sales) {
                if (sale.getIsVoided() != null && sale.getIsVoided()) {
                    continue;
                }
                var discountAllocations = SaleDiscountUtils.allocateDiscounts(sale);
                for (var item : sale.getItems()) {
                    int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                    if (quantitySold <= 0) {
                        continue;
                    }
                    revenue = revenue.add(SaleDiscountUtils.netItemRevenue(
                            item, discountAllocations.getOrDefault(item, BigDecimal.ZERO), quantitySold));
                    cogs = cogs.add(CostCalculationUtils.calculateCogs(item.getCostPriceAtSale(), quantitySold));
                }
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("periodLabel", label);
            point.put("revenue", revenue);
            point.put("cogs", cogs);
            point.put("grossProfit", revenue.subtract(cogs));
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
    // Â§5 Period-over-Period Comparison â€” populates previous period fields
    // -----------------------------------------------------------------------
    public AccountingSummaryResponse getAccountingSummary(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> profitSummary = getProfitSummary(startDate, endDate);
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        BigDecimal totalExpenses = BigDecimal.ZERO;
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (Expense expense : expenseRepository.findFiltered(null, startDate, endDate)) {
            if (expense.getDeletedAt() != null) {
                continue;
            }
            totalExpenses = totalExpenses.add(expense.getAmount());
            byCategory.merge(expense.getCategory() != null ? expense.getCategory().name() : "OTHER",
                    expense.getAmount(), BigDecimal::add);
        }

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
                    .divide(prevIncome, 1, RoundingMode.HALF_UP);
            response.setIncomeChangePercent(incomeChange);
        } else {
            response.setIncomeChangePercent(null);
        }

        BigDecimal currentNetProfit = response.getNetProfit();
        if (prevNetProfit != null && prevNetProfit.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal profitChange = currentNetProfit.subtract(prevNetProfit)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(prevNetProfit, 1, RoundingMode.HALF_UP);
            response.setProfitChangePercent(profitChange);
        } else {
            response.setProfitChangePercent(null);
        }

        return response;
    }

    /** Lightweight summary for previous-period comparison â€” no previous-period recursion. */
    private AccountingSummaryResponse computeRawSummary(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        // Same tax-exclusive, net-of-returns definition as the current period so
        // income / profit change percentages compare like-for-like.
        Map<String, Object> profitSummary = getProfitSummary(startDate, endDate);
        BigDecimal totalIncome = (BigDecimal) profitSummary.get("revenue");
        BigDecimal totalCogs = (BigDecimal) profitSummary.get("cogs");

        BigDecimal totalRefunds = BigDecimal.ZERO;
        for (SaleReturn saleReturn : saleReturnRepository.findByReturnDateBetween(startDateTime, endDateTime)) {
            totalRefunds = totalRefunds.add(saleReturn.getTotalReturnAmount() != null ? saleReturn.getTotalReturnAmount() : BigDecimal.ZERO);
        }

        BigDecimal totalExpenses = BigDecimal.ZERO;
        for (Expense expense : expenseRepository.findFiltered(null, startDate, endDate)) {
            if (expense.getDeletedAt() != null) continue;
            totalExpenses = totalExpenses.add(expense.getAmount());
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
    // Â§1 Dead Stock / Slow-Moving Inventory
    // -----------------------------------------------------------------------
    public List<DeadStockDto> getDeadStock(int daysThreshold) {
        LocalDateTime cutoffDate = LocalDate.now().minusDays(daysThreshold).atStartOfDay();
        List<Object[]> rows = productRepository.findDeadStock(cutoffDate);
        LocalDateTime now = LocalDateTime.now();

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
    // Â§2 Sales Timing Heatmap
    // -----------------------------------------------------------------------
    public List<SalesTimingDto> getSalesTiming(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        List<Sale> sales = saleRepository.findByDateRange(startDateTime, endDateTime, PageRequest.of(0, REPORT_MAX_ROWS)).getContent();

        // Key: dayOfWeek (1=Mon..7=Sun) + hourOfDay
        Map<String, SalesTimingDto> map = new LinkedHashMap<>();

        for (Sale sale : sales) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) continue;
            if (sale.getSaleDate() == null) continue;

            int dayOfWeek = sale.getSaleDate().getDayOfWeek().getValue(); // 1=Mon..7=Sun
            int hourOfDay = sale.getSaleDate().getHour();
            String key = dayOfWeek + "_" + hourOfDay;

            BigDecimal revenue = calculateNetSaleRevenue(sale);
            map.computeIfAbsent(key, k -> new SalesTimingDto(dayOfWeek, hourOfDay, 0L, BigDecimal.ZERO));
            SalesTimingDto dto = map.get(key);
            dto.setTransactionCount(dto.getTransactionCount() + 1);
            dto.setTotalRevenue(dto.getTotalRevenue().add(revenue));
        }

        return new ArrayList<>(map.values());
    }

    // -----------------------------------------------------------------------
    // Â§4 Basket Analysis â€” Frequently Bought Together
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
    // Â§3 Customer Retention & Lifetime Value
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
        YearMonth thisMonth = YearMonth.now();
        YearMonth lastMonth = thisMonth.minusMonths(1);

        LocalDateTime lastMonthStart = lastMonth.atDay(1).atStartOfDay();
        LocalDateTime lastMonthEnd = thisMonth.atDay(1).atStartOfDay();
        LocalDateTime thisMonthStart = thisMonth.atDay(1).atStartOfDay();
        LocalDateTime thisMonthEnd = thisMonth.atEndOfMonth().plusDays(1).atStartOfDay();

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
        var products = productRepository.findAll();
        
        BigDecimal totalInventoryValue = BigDecimal.ZERO;
        int totalProducts = 0;
        int lowStockProducts = 0;
        List<Map<String, Object>> lowStockList = new ArrayList<>();

        for (var product : products) {
            totalProducts++;
            BigDecimal unitCost = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
            BigDecimal value = unitCost.multiply(BigDecimal.valueOf(product.getStockQuantity() == null ? 0 : product.getStockQuantity()));
            totalInventoryValue = totalInventoryValue.add(value);

            int stock = product.getStockQuantity() == null ? 0 : product.getStockQuantity();
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
    // Shared helpers
    // -----------------------------------------------------------------------
    private int effectiveSoldQuantity(Integer quantity, Integer quantityRefunded) {
        int sold = quantity != null ? quantity : 0;
        int refunded = quantityRefunded != null ? quantityRefunded : 0;
        return Math.max(0, sold - refunded);
    }

    private BigDecimal calculateNetSaleRevenue(Sale sale) {
        return SaleDiscountUtils.netSaleRevenue(sale);
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
        LocalDate from = startDate != null ? startDate : LocalDate.now();
        LocalDate to = endDate != null ? endDate : from;
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }

        List<CashShiftResponse> shifts = cashShiftService.getShiftReport(
                from.atStartOfDay(), to.atTime(LocalTime.MAX), cashierId, shiftType);

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
        LocalDate from = startDate != null ? startDate : LocalDate.now();
        LocalDate to = endDate != null ? endDate : from;
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }

        List<CashDrawerSession> sessions = cashDrawerSessionRepository.findForReport(
                from.atStartOfDay(), to.atTime(LocalTime.MAX));
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
        LocalDate day = date != null ? date : LocalDate.now();
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.atTime(LocalTime.MAX);

        List<Sale> sales = saleRepository.findByDateRange(start, end,
                PageRequest.of(0, REPORT_MAX_ROWS)).getContent();
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
                    && CashVarianceUtil.exceedsTolerance(shift.getVariance(), CashVarianceUtil.DEFAULT_TOLERANCE)) {
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
                    && CashVarianceUtil.exceedsTolerance(drawer.getVariance(), CashVarianceUtil.DEFAULT_TOLERANCE)) {
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
        report.put("generatedAt", LocalDateTime.now());
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
     * Tax for the non-refunded units of a line. The stored tax covers the full
     * quantity, so partially refunded lines must not keep reporting all of it.
     */
    private BigDecimal prorateTax(SaleItem item, int quantitySold) {
        BigDecimal tax = item.getTaxAmount() != null ? item.getTaxAmount() : BigDecimal.ZERO;
        int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
        if (quantity <= 0 || quantitySold >= quantity) {
            return tax;
        }
        return tax.multiply(BigDecimal.valueOf(quantitySold))
                .divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
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
        List<Sale> sales = saleRepository.findByDateRange(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay(), PageRequest.of(0, REPORT_MAX_ROWS)).getContent();
        Map<Long, BigDecimal> revenueByProduct = new HashMap<>();
        for (Sale sale : sales) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) continue;
            var discountAllocations = SaleDiscountUtils.allocateDiscounts(sale);
            for (var item : sale.getItems()) {
                int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                if (quantitySold <= 0) continue;
                Long productId = item.getProduct().getId();
                BigDecimal itemRevenue = SaleDiscountUtils.netItemRevenue(
                        item, discountAllocations.getOrDefault(item, BigDecimal.ZERO), quantitySold);
                revenueByProduct.merge(productId, itemRevenue, BigDecimal::add);
            }
        }
        return revenueByProduct;
    }

    private Map<Long, BigDecimal> computeCategoryRevenue(LocalDate startDate, LocalDate endDate) {
        List<Sale> sales = saleRepository.findByDateRange(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay(), PageRequest.of(0, REPORT_MAX_ROWS)).getContent();
        Map<Long, BigDecimal> revenueByCategory = new HashMap<>();
        for (Sale sale : sales) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) continue;
            var discountAllocations = SaleDiscountUtils.allocateDiscounts(sale);
            for (var item : sale.getItems()) {
                int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                if (quantitySold <= 0) continue;
                Long categoryId = item.getProduct().getCategory() != null ? item.getProduct().getCategory().getId() : -1L;
                BigDecimal itemRevenue = SaleDiscountUtils.netItemRevenue(
                        item, discountAllocations.getOrDefault(item, BigDecimal.ZERO), quantitySold);
                revenueByCategory.merge(categoryId, itemRevenue, BigDecimal::add);
            }
        }
        return revenueByCategory;
    }
}
