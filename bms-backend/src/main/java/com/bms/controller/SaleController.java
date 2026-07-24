package com.bms.controller;

import com.bms.dto.request.CartVerifyRequest;
import com.bms.dto.request.RefundRequest;
import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.response.*;
import com.bms.entity.Sale;
import com.bms.service.SaleService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/sales")
public class SaleController {

    @Autowired
    private SaleService saleService;

    @Autowired
    private com.bms.service.UserService userService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<SaleResponse>>> getAllSales(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "saleDate") String sortBy,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String invoice) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).descending());
        Page<SaleResponse> sales = saleService.getFilteredSales(range, startDate, endDate, customerId, invoice, pageable)
                .map(saleService::convertToResponse);
        return ResponseEntity.ok(new ApiResponse<>(true, "Sales retrieved successfully", sales));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<SaleResponse>> getSaleById(@PathVariable Long id) {
        SaleResponse sale = saleService.getSaleById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Sale retrieved successfully", sale));
    }

    @GetMapping("/invoice/{invoiceNumber}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<SaleResponse>> getSaleByInvoiceNumber(@PathVariable String invoiceNumber) {
        SaleResponse sale = saleService.getSaleByInvoiceNumber(invoiceNumber);
        return ResponseEntity.ok(new ApiResponse<>(true, "Sale retrieved successfully", sale));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<SaleResponse>> createSale(
            @Valid @RequestBody SaleCreateRequest request,
            Authentication authentication) {
        // Extract cashier ID from authentication
        org.springframework.security.core.userdetails.UserDetails userDetails = 
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long cashierId = userService.findByUsername(userDetails.getUsername()).getId();
        SaleResponse sale = saleService.createSale(request, cashierId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true, "Sale created successfully", sale));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<SaleResponse>> voidSale(
            @PathVariable Long id,
            @RequestParam String reason,
            Authentication authentication) {
        // Extract authenticated user from SecurityContext
        org.springframework.security.core.userdetails.UserDetails userDetails = 
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        SaleResponse sale = saleService.voidSale(id, userId, reason);
        return ResponseEntity.ok(new ApiResponse<>(true, "Sale voided successfully", sale));
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<RefundResponse>> refundSale(
            @PathVariable Long id,
            @Valid @RequestBody RefundRequest request,
            Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails =
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        RefundResponse refund = saleService.processRefund(id, request, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Refund processed", refund));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteSale(@PathVariable Long id, Authentication authentication) {
        // Extract authenticated user from SecurityContext
        org.springframework.security.core.userdetails.UserDetails userDetails = 
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        saleService.deleteSale(id, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Sale deleted successfully", null));
    }

    @GetMapping("/date-range")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<SaleResponse>>> getSalesByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Pageable pageable = PageRequest.of(0, 1000, Sort.by("saleDate").descending());
        Page<Sale> sales = saleService.getAllSales(pageable);
        
        List<SaleResponse> filteredSales = sales.stream()
            .filter(sale -> !sale.getSaleDate().toLocalDate().isBefore(startDate) && 
                           !sale.getSaleDate().toLocalDate().isAfter(endDate))
            .map(saleService::convertToResponse)
            .toList();
        
        return ResponseEntity.ok(new ApiResponse<>(true, "Sales retrieved successfully", filteredSales));
    }

    @PostMapping("/verify-cart")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CartVerifyResponse>> verifyCart(
            @Valid @RequestBody CartVerifyRequest request) {
        CartVerifyResponse response = saleService.verifyCart(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Cart verified", response));
    }


    // Customer Statics
    @GetMapping("/customer/{customerId}/stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<CustomerStatsResponse>> getCustomerStats(@PathVariable Long customerId) {
        CustomerStatsResponse stats = saleService.getCustomerStats(customerId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Customer stats retrieved successfully", stats));
    }



    @GetMapping("/customer/{customerId}/top-products")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<CustomerTopProductResponse>>> getCustomerTopProducts(@PathVariable Long customerId) {
        List<CustomerTopProductResponse> topProducts = saleService.getCustomerTopProducts(customerId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Top products retrieved successfully", topProducts));
    }

    @GetMapping("/customer/{customerId}/daily-spending/{year}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<CustomerDailySpendingResponse>>> getCustomerDailySpending(
            @PathVariable Long customerId,
            @PathVariable int year) {
        List<CustomerDailySpendingResponse> dailySpending = saleService.getCustomerDailySpending(customerId, year);
        return ResponseEntity.ok(new ApiResponse<>(true, "Daily spending retrieved successfully", dailySpending));
    }
}
