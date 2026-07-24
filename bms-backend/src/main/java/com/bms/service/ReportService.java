package com.bms.service;

import com.bms.dto.response.AccountingSummaryResponse;
import com.bms.dto.response.DailySalesTrendDto;
import com.bms.dto.response.SupplierProfitDto;
import java.util.Locale;
import com.bms.entity.Expense;
import com.bms.entity.Refund;
import com.bms.entity.Sale;
import com.bms.repository.ExpenseRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.RefundRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.StockMovementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class ReportService {

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
    private RefundRepository refundRepository;

    // Daily sales report
    public Map<String, Object> getDailySalesReport(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, 1000);
        Page<Sale> salesPage = saleRepository.findByDateRange(startOfDay, endOfDay, pageable);
        List<Sale> sales = salesPage.getContent();
        
        BigDecimal totalRevenue = BigDecimal.ZERO;
        int totalTransactions = 0;
        Map<Long, Integer> productQuantities = new HashMap<>();

        for (Sale sale : sales) {
            if (!sale.getIsVoided()) {
                totalRevenue = totalRevenue.add(calculateNetSaleRevenue(sale));
                totalTransactions++;
                
                for (var item : sale.getItems()) {
                    int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                    if (quantitySold > 0) {
                        productQuantities.merge(item.getProduct().getId(), quantitySold, Integer::sum);
                    }
                }
            }
        }

        Map<String, Object> report = new HashMap<>();
        report.put("date", date);
        report.put("totalRevenue", totalRevenue);
        report.put("totalTransactions", totalTransactions);
        report.put("averageTransactionValue", totalTransactions > 0 ? totalRevenue.divide(BigDecimal.valueOf(totalTransactions), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);

        return report;
    }

    // Monthly sales report
    public Map<String, Object> getMonthlySalesReport(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);
        
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, 1000);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        BigDecimal totalRevenue = BigDecimal.ZERO;
        int totalTransactions = 0;

        for (Sale sale : sales) {
            if (!sale.getIsVoided()) {
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

        Pageable pageable = PageRequest.of(0, 1000);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        Map<Long, Map<String, Object>> productStats = new HashMap<>();

        for (Sale sale : sales) {
            if (!sale.getIsVoided()) {
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
                    stats.put("totalRevenue", ((BigDecimal) stats.get("totalRevenue")).add(item.getUnitPrice().multiply(BigDecimal.valueOf(quantitySold))));
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

    // Cashier performance report
    public List<Map<String, Object>> getCashierPerformanceReport(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, 1000);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        Map<Long, Map<String, Object>> cashierStats = new HashMap<>();

        for (Sale sale : sales) {
            if (!sale.getIsVoided()) {
                Long cashierId = sale.getCashierId();
                
                cashierStats.computeIfAbsent(cashierId, k -> {
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("cashierId", cashierId);
                    stats.put("totalSales", BigDecimal.ZERO);
                    stats.put("transactionCount", 0);
                    return stats;
                });
                
                Map<String, Object> stats = cashierStats.get(cashierId);
                stats.put("totalSales", ((BigDecimal) stats.get("totalSales")).add(calculateNetSaleRevenue(sale)));
                stats.put("transactionCount", (Integer) stats.get("transactionCount") + 1);
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

        Pageable pageable = PageRequest.of(0, 1000);
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

    public List<Map<String, Object>> getTopProducts(String period, int limit) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = resolveStartDate(period, endDate);
        List<Sale> sales = saleRepository.findByDateRange(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay(), PageRequest.of(0, 1000)).getContent();

        Map<Long, Map<String, Object>> stats = new LinkedHashMap<>();
        for (Sale sale : sales) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) {
                continue;
            }
            for (var item : sale.getItems()) {
                int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                if (quantitySold <= 0) {
                    continue;
                }
                Long productId = item.getProduct().getId();
                stats.computeIfAbsent(productId, k -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("productId", productId);
                    entry.put("productName", item.getProduct().getName());
                    entry.put("quantitySold", 0);
                    entry.put("revenue", BigDecimal.ZERO);
                    entry.put("profit", BigDecimal.ZERO);
                    return entry;
                });
                Map<String, Object> entry = stats.get(productId);
                entry.put("quantitySold", ((Integer) entry.get("quantitySold")) + quantitySold);
                entry.put("revenue", ((BigDecimal) entry.get("revenue")).add(item.getUnitPrice().multiply(BigDecimal.valueOf(quantitySold))));
                entry.put("profit", ((BigDecimal) entry.get("profit")).add(CostCalculationUtils.calculateProfit(item.getUnitPrice(), item.getCostPriceAtSale(), quantitySold)));
            }
        }

        List<Map<String, Object>> report = new ArrayList<>(stats.values());
        report.sort((a, b) -> ((BigDecimal) b.get("revenue")).compareTo((BigDecimal) a.get("revenue")));
        return report.stream().limit(limit).toList();
    }

    public List<Map<String, Object>> getTopCategories(String period) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = resolveStartDate(period, endDate);
        List<Sale> sales = saleRepository.findByDateRange(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay(), PageRequest.of(0, 1000)).getContent();

        Map<Long, Map<String, Object>> stats = new LinkedHashMap<>();
        for (Sale sale : sales) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) {
                continue;
            }
            for (var item : sale.getItems()) {
                int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                if (quantitySold <= 0) {
                    continue;
                }
                Long categoryId = item.getProduct().getCategory() != null ? item.getProduct().getCategory().getId() : null;
                String categoryName = item.getProduct().getCategory() != null ? item.getProduct().getCategory().getName() : "Uncategorized";
                stats.computeIfAbsent(categoryId != null ? categoryId : -1L, k -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("categoryId", categoryId);
                    entry.put("categoryName", categoryName);
                    entry.put("revenue", BigDecimal.ZERO);
                    entry.put("profit", BigDecimal.ZERO);
                    return entry;
                });
                Map<String, Object> entry = stats.get(categoryId != null ? categoryId : -1L);
                entry.put("revenue", ((BigDecimal) entry.get("revenue")).add(item.getUnitPrice().multiply(BigDecimal.valueOf(quantitySold))));
                entry.put("profit", ((BigDecimal) entry.get("profit")).add(CostCalculationUtils.calculateProfit(item.getUnitPrice(), item.getCostPriceAtSale(), quantitySold)));
            }
        }

        List<Map<String, Object>> report = new ArrayList<>(stats.values());
        report.sort((a, b) -> ((BigDecimal) b.get("revenue")).compareTo((BigDecimal) a.get("revenue")));
        return report;
    }

    public Map<String, Object> getProfitSummary(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();
        List<Sale> sales = saleRepository.findByDateRange(startDateTime, endDateTime, PageRequest.of(0, 1000)).getContent();

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        for (Sale sale : sales) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) {
                continue;
            }
            for (var item : sale.getItems()) {
                int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                if (quantitySold <= 0) {
                    continue;
                }
                revenue = revenue.add(item.getUnitPrice().multiply(BigDecimal.valueOf(quantitySold)));
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
            List<Sale> sales = saleRepository.findByDateRange(startDateTime, endDateTime, PageRequest.of(0, 1000)).getContent();
            BigDecimal revenue = BigDecimal.ZERO;
            BigDecimal cogs = BigDecimal.ZERO;
            for (Sale sale : sales) {
                if (sale.getIsVoided() != null && sale.getIsVoided()) {
                    continue;
                }
                for (var item : sale.getItems()) {
                    int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
                    if (quantitySold <= 0) {
                        continue;
                    }
                    revenue = revenue.add(item.getUnitPrice().multiply(BigDecimal.valueOf(quantitySold)));
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

        BigDecimal totalIncome = BigDecimal.ZERO;
        for (Sale sale : saleRepository.findByDateRange(startDateTime, endDateTime, PageRequest.of(0, 1000)).getContent()) {
            if (sale.getIsVoided() != null && sale.getIsVoided()) {
                continue;
            }
            totalIncome = totalIncome.add(sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO);
        }

        BigDecimal totalRefunds = BigDecimal.ZERO;
        for (Refund refund : refundRepository.findByRefundDateBetween(startDateTime, endDateTime)) {
            totalRefunds = totalRefunds.add(refund.getTotalRefundAmount() != null ? refund.getTotalRefundAmount() : BigDecimal.ZERO);
        }

        BigDecimal totalCogs = (BigDecimal) profitSummary.get("cogs");
        BigDecimal grossProfit = totalIncome.subtract(totalRefunds).subtract(totalCogs);

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
        return response;
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
            dto.setTotalSuppliedCost((BigDecimal) row.get("total_supplied_cost"));
            dto.setEstimatedRevenue((BigDecimal) row.get("estimated_revenue"));

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

    private int effectiveSoldQuantity(Integer quantity, Integer quantityRefunded) {
        int sold = quantity != null ? quantity : 0;
        int refunded = quantityRefunded != null ? quantityRefunded : 0;
        return Math.max(0, sold - refunded);
    }

    private BigDecimal calculateNetSaleRevenue(Sale sale) {
        BigDecimal revenue = BigDecimal.ZERO;
        for (var item : sale.getItems()) {
            int quantitySold = effectiveSoldQuantity(item.getQuantity(), item.getQuantityRefunded());
            if (quantitySold > 0 && item.getUnitPrice() != null) {
                revenue = revenue.add(item.getUnitPrice().multiply(BigDecimal.valueOf(quantitySold)));
            }
        }
        return revenue;
    }

    private LocalDate resolveStartDate(String period, LocalDate endDate) {
        return switch (period != null ? period.toUpperCase(Locale.ROOT) : "MONTH") {
            case "WEEK" -> endDate.minusWeeks(1);
            case "YEAR" -> endDate.minusYears(1);
            default -> endDate.minusMonths(1);
        };
    }

    // Inventory report
    public Map<String, Object> getInventoryReport() {
        var products = productRepository.findAll();
        
        BigDecimal totalInventoryValue = BigDecimal.ZERO;
        int totalProducts = 0;
        int lowStockProducts = 0;
        List<Map<String, Object>> lowStockList = new ArrayList<>();

        for (var product : products) {
            totalProducts++;
            BigDecimal value = product.getCostPrice().multiply(BigDecimal.valueOf(product.getStockQuantity()));
            totalInventoryValue = totalInventoryValue.add(value);
            
            if (product.getStockQuantity() <= product.getMinStockLevel()) {
                lowStockProducts++;
                Map<String, Object> item = new HashMap<>();
                item.put("productId", product.getId());
                item.put("productName", product.getName());
                item.put("currentStock", product.getStockQuantity());
                item.put("minStockLevel", product.getMinStockLevel());
                item.put("shortage", product.getMinStockLevel() - product.getStockQuantity());
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
}
