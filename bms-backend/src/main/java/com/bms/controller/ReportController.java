package com.bms.controller;

import com.bms.dto.response.AccountingSummaryResponse;
import com.bms.dto.response.ApiResponse;
import com.bms.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    @Autowired
    private ReportService reportService;

    @GetMapping("/daily-sales")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDailySalesReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Map<String, Object> report = reportService.getDailySalesReport(date);
        return ResponseEntity.ok(new ApiResponse<>(true, "Daily sales report retrieved successfully", report));
    }

    @GetMapping("/monthly-sales")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonthlySalesReport(
            @RequestParam int year,
            @RequestParam int month) {
        Map<String, Object> report = reportService.getMonthlySalesReport(year, month);
        return ResponseEntity.ok(new ApiResponse<>(true, "Monthly sales report retrieved successfully", report));
    }

    @GetMapping("/product-performance")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getProductPerformanceReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<Map<String, Object>> report = reportService.getProductPerformanceReport(startDate, endDate);
        return ResponseEntity.ok(new ApiResponse<>(true, "Product performance report retrieved successfully", report));
    }

    @GetMapping("/top-selling-products")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopSellingProducts(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<Map<String, Object>> report = reportService.getTopSellingProducts(limit, startDate, endDate);
        return ResponseEntity.ok(new ApiResponse<>(true, "Top selling products retrieved successfully", report));
    }

    @GetMapping("/cashier-performance")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getCashierPerformanceReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<Map<String, Object>> report = reportService.getCashierPerformanceReport(startDate, endDate);
        return ResponseEntity.ok(new ApiResponse<>(true, "Cashier performance report retrieved successfully", report));
    }

    @GetMapping("/sales-trend")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<com.bms.dto.response.DailySalesTrendDto>>> getSalesTrend(
            @RequestParam(defaultValue = "7") int days) {
        List<com.bms.dto.response.DailySalesTrendDto> report = reportService.getSalesTrend(days);
        return ResponseEntity.ok(new ApiResponse<>(true, "Sales trend retrieved successfully", report));
    }

    @GetMapping("/top-products")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopProducts(
            @RequestParam(defaultValue = "MONTH") String period,
            @RequestParam(defaultValue = "10") int limit) {
        List<Map<String, Object>> report = reportService.getTopProducts(period, limit);
        return ResponseEntity.ok(new ApiResponse<>(true, "Top products report retrieved successfully", report));
    }

    @GetMapping("/top-categories")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopCategories(
            @RequestParam(defaultValue = "MONTH") String period) {
        List<Map<String, Object>> report = reportService.getTopCategories(period);
        return ResponseEntity.ok(new ApiResponse<>(true, "Top categories report retrieved successfully", report));
    }

    @GetMapping("/profit-summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProfitSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> report = reportService.getProfitSummary(startDate, endDate);
        return ResponseEntity.ok(new ApiResponse<>(true, "Profit summary retrieved successfully", report));
    }

    @GetMapping("/profit-trend")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getProfitTrend(
            @RequestParam(defaultValue = "MONTH") String period,
            @RequestParam(defaultValue = "12") int points) {
        List<Map<String, Object>> report = reportService.getProfitTrend(period, points);
        return ResponseEntity.ok(new ApiResponse<>(true, "Profit trend retrieved successfully", report));
    }

    @GetMapping("/accounting-summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AccountingSummaryResponse>> getAccountingSummary(
            @RequestParam int year,
            @RequestParam int month) {
        AccountingSummaryResponse report = reportService.getAccountingSummary(year, month);
        return ResponseEntity.ok(new ApiResponse<>(true, "Accounting summary retrieved successfully", report));
    }

    @GetMapping("/inventory")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInventoryReport() {
        Map<String, Object> report = reportService.getInventoryReport();
        return ResponseEntity.ok(new ApiResponse<>(true, "Inventory report retrieved successfully", report));
    }
}
