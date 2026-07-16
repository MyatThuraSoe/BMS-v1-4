package com.bms.service;

import com.bms.entity.Expense;
import com.bms.entity.ExpenseCategory;
import com.bms.entity.Refund;
import com.bms.entity.Sale;
import com.bms.repository.ExpenseRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.RefundRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.dto.response.AccountingSummaryResponse;
import com.bms.dto.response.DailySalesTrendDto;
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
    private ExpenseRepository expenseRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                totalRevenue = totalRevenue.add(sale.getTotalAmount());
                totalTransactions++;
                
                for (var item : sale.getItems()) {
                    productQuantities.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
                }
            }
        }

        Map<String, Object> report = new HashMap<>();
        report.put("date", date);
        report.put("totalRevenue", totalRevenue);
        report.put("totalTransactions", totalTransactions);
        report.put("averageTransactionValue", totalTransactions > 0 ? totalRevenue.divide(BigDecimal.valueOf(totalTransactions), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO);

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
                totalRevenue = totalRevenue.add(sale.getTotalAmount());
                totalTransactions++;
            }
        }

        Map<String, Object> report = new HashMap<>();
        report.put("year", year);
        report.put("month", month);
        report.put("totalRevenue", totalRevenue);
        report.put("totalTransactions", totalTransactions);
        report.put("averageTransactionValue", totalTransactions > 0 ? totalRevenue.divide(BigDecimal.valueOf(totalTransactions), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO);

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
                    stats.put("totalQuantitySold", (Integer) stats.get("totalQuantitySold") + item.getQuantity());
                    stats.put("totalRevenue", ((BigDecimal) stats.get("totalRevenue")).add(item.getTotalPrice()));
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
                stats.put("totalSales", ((BigDecimal) stats.get("totalSales")).add(sale.getTotalAmount()));
                stats.put("transactionCount", (Integer) stats.get("transactionCount") + 1);
            }
        }

        List<Map<String, Object>> report = new ArrayList<>(cashierStats.values());
        report.sort((a, b) -> ((BigDecimal) b.get("totalSales")).compareTo((BigDecimal) a.get("totalSales")));
        
        return report;
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

    // Sales trend report - returns daily sales for the last N days
    public List<DailySalesTrendDto> getSalesTrend(int days) {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(days - 1);
        
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = today.plusDays(1).atStartOfDay();

        // Fetch all non-voided sales in the date range
        Pageable pageable = PageRequest.of(0, 10000);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        // Group sales by date and calculate totals
        Map<LocalDate, DailySalesTrendDto> dailyMap = new HashMap<>();
        
        // Initialize all dates with zero values to ensure contiguous range
        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            dailyMap.put(date, new DailySalesTrendDto(date, BigDecimal.ZERO, 0));
        }
        
        // Aggregate actual sales data
        for (Sale sale : sales) {
            if (!sale.getIsVoided()) {
                LocalDate saleDate = sale.getSaleDate().toLocalDate();
                DailySalesTrendDto dto = dailyMap.get(saleDate);
                if (dto != null) {
                    dto.setTotalSales(dto.getTotalSales().add(sale.getTotalAmount()));
                    dto.setTransactionCount(dto.getTransactionCount() + 1);
                }
            }
        }

        // Convert to list sorted by date
        List<DailySalesTrendDto> result = new ArrayList<>(dailyMap.values());
        result.sort(Comparator.comparing(DailySalesTrendDto::getDate));
        
        return result;
    }

    /**
     * Calculate gross profit for a date range.
     * Profit = (unitPrice - costPriceAtSale) * quantity for each sale item.
     * Only includes non-voided, non-deleted sales.
     */
    public Map<String, Object> getProfitReport(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, 10000);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCOGS = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        int totalTransactions = 0;

        for (Sale sale : sales) {
            // Exclude voided and deleted sales from profit calculation
            if (!sale.getIsVoided() && sale.getDeletedAt() == null && sale.getIsActive()) {
                totalTransactions++;
                
                for (var item : sale.getItems()) {
                    BigDecimal unitPrice = item.getUnitPrice();
                    BigDecimal costPriceAtSale = item.getCostPriceAtSale() != null 
                        ? item.getCostPriceAtSale() : BigDecimal.ZERO;
                    int quantity = item.getQuantity();
                    
                    BigDecimal lineRevenue = unitPrice.multiply(BigDecimal.valueOf(quantity));
                    BigDecimal lineCOGS = costPriceAtSale.multiply(BigDecimal.valueOf(quantity));
                    BigDecimal lineProfit = lineRevenue.subtract(lineCOGS);
                    
                    totalRevenue = totalRevenue.add(lineRevenue);
                    totalCOGS = totalCOGS.add(lineCOGS);
                    totalProfit = totalProfit.add(lineProfit);
                }
            }
        }

        Map<String, Object> report = new HashMap<>();
        report.put("startDate", startDate);
        report.put("endDate", endDate);
        report.put("totalRevenue", totalRevenue);
        report.put("totalCOGS", totalCOGS);
        report.put("totalProfit", totalProfit);
        report.put("grossMarginPercent", totalRevenue.compareTo(BigDecimal.ZERO) > 0 
            ? totalProfit.multiply(BigDecimal.valueOf(100)).divide(totalRevenue, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO);
        report.put("totalTransactions", totalTransactions);

        return report;
    }

    /**
     * Get date range for a given period (WEEK, MONTH, YEAR) relative to today.
     */
    private LocalDate[] getPeriodDateRange(String period) {
        LocalDate today = LocalDate.now();
        LocalDate startDate;
        
        switch (period.toUpperCase()) {
            case "WEEK":
                startDate = today.minusDays(6); // Last 7 days including today
                break;
            case "YEAR":
                startDate = today.minusYears(1).plusDays(1); // Last year
                break;
            case "MONTH":
            default:
                startDate = today.withDayOfMonth(1); // Current month
                break;
        }
        
        return new LocalDate[]{startDate, today};
    }

    /**
     * Top products by revenue and profit for a given period.
     * Returns: productName, quantitySold, revenue, cogs, grossProfit, grossMarginPercent
     */
    public List<Map<String, Object>> getTopProducts(String period, int limit) {
        LocalDate[] dateRange = getPeriodDateRange(period);
        LocalDate startDate = dateRange[0];
        LocalDate endDate = dateRange[1];
        
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, 10000);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        Map<Long, Map<String, Object>> productStats = new HashMap<>();

        for (Sale sale : sales) {
            if (!sale.getIsVoided() && sale.getDeletedAt() == null && sale.getIsActive()) {
                for (var item : sale.getItems()) {
                    Long productId = item.getProduct().getId();
                    
                    productStats.computeIfAbsent(productId, k -> {
                        Map<String, Object> stats = new HashMap<>();
                        stats.put("productId", productId);
                        stats.put("productName", item.getProduct().getName());
                        stats.put("categoryName", item.getProduct().getCategory() != null 
                            ? item.getProduct().getCategory().getName() : "Uncategorized");
                        stats.put("totalQuantitySold", 0);
                        stats.put("revenue", BigDecimal.ZERO);
                        stats.put("cogs", BigDecimal.ZERO);
                        stats.put("grossProfit", BigDecimal.ZERO);
                        return stats;
                    });
                    
                    Map<String, Object> stats = productStats.get(productId);
                    int quantity = item.getQuantity();
                    BigDecimal unitPrice = item.getUnitPrice();
                    BigDecimal costPriceAtSale = item.getCostPriceAtSale() != null 
                        ? item.getCostPriceAtSale() : BigDecimal.ZERO;
                    
                    BigDecimal lineRevenue = unitPrice.multiply(BigDecimal.valueOf(quantity));
                    BigDecimal lineCOGS = costPriceAtSale.multiply(BigDecimal.valueOf(quantity));
                    BigDecimal lineProfit = lineRevenue.subtract(lineCOGS);
                    
                    stats.put("totalQuantitySold", (Integer) stats.get("totalQuantitySold") + quantity);
                    stats.put("revenue", ((BigDecimal) stats.get("revenue")).add(lineRevenue));
                    stats.put("cogs", ((BigDecimal) stats.get("cogs")).add(lineCOGS));
                    stats.put("grossProfit", ((BigDecimal) stats.get("grossProfit")).add(lineProfit));
                }
            }
        }

        List<Map<String, Object>> report = new ArrayList<>(productStats.values());
        // Sort by revenue descending
        report.sort((a, b) -> ((BigDecimal) b.get("revenue")).compareTo((BigDecimal) a.get("revenue")));
        
        // Calculate margin percent and apply limit
        for (Map<String, Object> item : report) {
            BigDecimal revenue = (BigDecimal) item.get("revenue");
            BigDecimal profit = (BigDecimal) item.get("grossProfit");
            item.put("grossMarginPercent", revenue.compareTo(BigDecimal.ZERO) > 0 
                ? profit.multiply(BigDecimal.valueOf(100)).divide(revenue, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        }
        
        if (report.size() > limit) {
            return report.subList(0, limit);
        }
        return report;
    }

    /**
     * Top categories by revenue for a given period.
     * Returns: categoryName, revenue, quantitySold, productCount
     */
    public List<Map<String, Object>> getTopCategories(String period) {
        LocalDate[] dateRange = getPeriodDateRange(period);
        LocalDate startDate = dateRange[0];
        LocalDate endDate = dateRange[1];
        
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, 10000);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        Map<String, Map<String, Object>> categoryStats = new HashMap<>();

        for (Sale sale : sales) {
            if (!sale.getIsVoided() && sale.getDeletedAt() == null && sale.getIsActive()) {
                for (var item : sale.getItems()) {
                    String categoryName = item.getProduct().getCategory() != null 
                        ? item.getProduct().getCategory().getName() : "Uncategorized";
                    
                    categoryStats.computeIfAbsent(categoryName, k -> {
                        Map<String, Object> stats = new HashMap<>();
                        stats.put("categoryName", categoryName);
                        stats.put("revenue", BigDecimal.ZERO);
                        stats.put("quantitySold", 0);
                        stats.put("productCount", 0);
                        stats.put("productIds", new HashSet<Long>());
                        return stats;
                    });
                    
                    Map<String, Object> stats = categoryStats.get(categoryName);
                    int quantity = item.getQuantity();
                    BigDecimal unitPrice = item.getUnitPrice();
                    BigDecimal lineRevenue = unitPrice.multiply(BigDecimal.valueOf(quantity));
                    
                    @SuppressWarnings("unchecked")
                    Set<Long> productIds = (Set<Long>) stats.get("productIds");
                    if (!productIds.contains(item.getProduct().getId())) {
                        productIds.add(item.getProduct().getId());
                        stats.put("productCount", productIds.size());
                    }
                    
                    stats.put("revenue", ((BigDecimal) stats.get("revenue")).add(lineRevenue));
                    stats.put("quantitySold", (Integer) stats.get("quantitySold") + quantity);
                }
            }
        }

        List<Map<String, Object>> report = new ArrayList<>(categoryStats.values());
        // Remove temporary productIds set
        for (Map<String, Object> item : report) {
            item.remove("productIds");
        }
        // Sort by revenue descending
        report.sort((a, b) -> ((BigDecimal) b.get("revenue")).compareTo((BigDecimal) a.get("revenue")));
        
        return report;
    }

    /**
     * Profit summary for an arbitrary date range - alias for getProfitReport with cleaner naming.
     */
    public Map<String, Object> getProfitSummary(LocalDate startDate, LocalDate endDate) {
        return getProfitReport(startDate, endDate);
    }

    /**
     * Profit trend over time periods (weekly/monthly/yearly).
     * Returns list of: { periodLabel, revenue, cogs, grossProfit }
     */
    public List<Map<String, Object>> getProfitTrend(String period, int points) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        
        for (int i = points - 1; i >= 0; i--) {
            LocalDate periodStart;
            LocalDate periodEnd;
            String periodLabel;
            
            switch (period.toUpperCase()) {
                case "WEEK":
                    periodEnd = today.minusWeeks(i);
                    periodStart = periodEnd.with(java.time.DayOfWeek.MONDAY);
                    periodEnd = periodEnd.with(java.time.DayOfWeek.SUNDAY);
                    periodLabel = "Week " + (i == points - 1 ? "This" : "Last " + (points - i));
                    break;
                case "YEAR":
                    periodEnd = today.minusYears(i);
                    periodStart = periodEnd.withMonth(1).withDayOfMonth(1);
                    periodEnd = periodEnd.withMonth(12).withDayOfMonth(31);
                    periodLabel = String.valueOf(today.getYear() - i);
                    break;
                case "MONTH":
                default:
                    periodEnd = today.minusMonths(i);
                    periodStart = periodEnd.withDayOfMonth(1);
                    periodEnd = periodEnd.withDayOfMonth(periodEnd.lengthOfMonth());
                    periodLabel = periodEnd.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
                    break;
            }
            
            // Calculate profit for this period
            Map<String, Object> periodData = calculateProfitForPeriod(periodStart, periodEnd);
            periodData.put("periodLabel", periodLabel);
            periodData.put("periodStart", periodStart);
            periodData.put("periodEnd", periodEnd);
            result.add(periodData);
        }
        
        return result;
    }

    /**
     * Helper method to calculate profit metrics for a specific date range.
     */
    private Map<String, Object> calculateProfitForPeriod(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Pageable pageable = PageRequest.of(0, 10000);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();
        
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalCOGS = BigDecimal.ZERO;
        
        for (Sale sale : sales) {
            if (!sale.getIsVoided() && sale.getDeletedAt() == null && sale.getIsActive()) {
                for (var item : sale.getItems()) {
                    BigDecimal unitPrice = item.getUnitPrice();
                    BigDecimal costPriceAtSale = item.getCostPriceAtSale() != null 
                        ? item.getCostPriceAtSale() : BigDecimal.ZERO;
                    int quantity = item.getQuantity();
                    
                    BigDecimal lineRevenue = unitPrice.multiply(BigDecimal.valueOf(quantity));
                    BigDecimal lineCOGS = costPriceAtSale.multiply(BigDecimal.valueOf(quantity));
                    
                    totalRevenue = totalRevenue.add(lineRevenue);
                    totalCOGS = totalCOGS.add(lineCOGS);
                }
            }
        }
        
        BigDecimal grossProfit = totalRevenue.subtract(totalCOGS);
        
        Map<String, Object> result = new HashMap<>();
        result.put("revenue", totalRevenue);
        result.put("cogs", totalCOGS);
        result.put("grossProfit", grossProfit);
        
        return result;
    }

    /**
     * Accounting summary for a specific month.
     * Returns income, COGS, gross profit, expenses, net profit, expenses by category, and total refunds.
     */
    public AccountingSummaryResponse getAccountingSummary(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1).minusDays(1);
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        // Calculate total income and COGS from sales
        Pageable pageable = PageRequest.of(0, 10000);
        Page<Sale> salesPage = saleRepository.findByDateRange(startDateTime, endDateTime, pageable);
        List<Sale> sales = salesPage.getContent();

        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalCogs = BigDecimal.ZERO;

        for (Sale sale : sales) {
            if (!sale.getIsVoided() && sale.getDeletedAt() == null && sale.getIsActive()) {
                totalIncome = totalIncome.add(sale.getTotalAmount());
                
                for (var item : sale.getItems()) {
                    BigDecimal costPriceAtSale = item.getCostPriceAtSale() != null
                        ? item.getCostPriceAtSale() : BigDecimal.ZERO;
                    int quantity = item.getQuantity();
                    BigDecimal lineCOGS = costPriceAtSale.multiply(BigDecimal.valueOf(quantity));
                    totalCogs = totalCogs.add(lineCOGS);
                }
            }
        }

        BigDecimal grossProfit = totalIncome.subtract(totalCogs);

        // Calculate total expenses and expenses by category
        BigDecimal totalExpenses = expenseRepository.sumByDateRange(startDate, endDate);
        if (totalExpenses == null) {
            totalExpenses = BigDecimal.ZERO;
        }

        List<AccountingSummaryResponse.CategoryAmount> expensesByCategory = new ArrayList<>();
        List<Object[]> categorySums = expenseRepository.sumByCategoryAndDateRange(startDate, endDate);
        for (Object[] row : categorySums) {
            ExpenseCategory category = (ExpenseCategory) row[0];
            BigDecimal amount = (BigDecimal) row[1];
            if (amount != null) {
                expensesByCategory.add(new AccountingSummaryResponse.CategoryAmount(
                    category.name(), amount));
            }
        }

        // Calculate total refunds for the month
        BigDecimal totalRefunds = refundRepository.sumByDateRange(startDateTime, endDateTime);
        if (totalRefunds == null) {
            totalRefunds = BigDecimal.ZERO;
        }

        BigDecimal netProfit = grossProfit.subtract(totalExpenses);

        return new AccountingSummaryResponse(
            totalIncome,
            totalCogs,
            grossProfit,
            totalExpenses,
            netProfit,
            expensesByCategory,
            totalRefunds
        );
    }
}
