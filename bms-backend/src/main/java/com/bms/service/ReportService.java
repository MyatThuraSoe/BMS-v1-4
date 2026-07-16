package com.bms.service;

import com.bms.entity.Sale;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.StockMovementRepository;
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
                totalRevenue = totalRevenue.add(sale.getTotalAmount());
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
}
