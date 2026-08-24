package com.bms.controller;

import com.bms.dto.request.CartVerifyRequest;
import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.request.SaleReturnRequest;
import com.bms.dto.response.*;
import com.bms.entity.Sale;
import com.bms.service.SaleReturnService;
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
import java.util.Map;

@RestController
@RequestMapping("/api/sales")
public class SaleController {

    @Autowired
    private SaleService saleService;

    @Autowired
    private SaleReturnService saleReturnService;

    @Autowired
    private com.bms.service.UserService userService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
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
        Page<SaleResponse> sales = saleService.convertToResponses(
                saleService.getFilteredSales(range, startDate, endDate, customerId, invoice, pageable));
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
        // Credit sales are STRICTLY limited to ADMIN/MANAGER at the API level
        // even though cashiers may post cash sales.
        if (isCreditRequest(request) && !hasManagerAuthority(authentication)) {
            throw new org.springframework.security.access.AccessDeniedException("Credit sales are restricted to managers");
        }
        // Extract cashier ID from authentication
        org.springframework.security.core.userdetails.UserDetails userDetails = 
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long cashierId = userService.findByUsername(userDetails.getUsername()).getId();
        SaleResponse sale = saleService.createSale(request, cashierId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true, "Sale created successfully", sale));
    }

    private boolean isCreditRequest(SaleCreateRequest request) {
        return request.getSaleType() != null && "CREDIT".equalsIgnoreCase(request.getSaleType().trim());
    }

    private boolean hasManagerAuthority(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_MANAGER".equals(a.getAuthority()));
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

    @PostMapping("/{id}/returns")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<SaleReturnResponse>> createSaleReturn(
            @PathVariable Long id,
            @Valid @RequestBody SaleReturnRequest request,
            Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails =
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        SaleReturnResponse saleReturn = saleReturnService.createSaleReturn(id, request, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Sale return processed", saleReturn));
    }

    @GetMapping("/{id}/returnable-items")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<ReturnableItemsResponse>> getReturnableItems(@PathVariable Long id) {
        ReturnableItemsResponse items = saleReturnService.getReturnableItems(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Returnable items retrieved successfully", items));
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

    @DeleteMapping("/old")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteOldSales(
            @RequestParam(defaultValue = "1") int olderThanYears,
            Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails =
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        Map<String, Object> result = saleService.deleteSalesOlderThanYears(olderThanYears, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Old sales deleted successfully", result));
    }

    @GetMapping("/date-range")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<SaleResponse>>> getSalesByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Pageable pageable = PageRequest.of(0, 1000, Sort.by("saleDate").descending());
        Page<Sale> sales = saleService.getAllSales(pageable);
        
        List<SaleResponse> filteredSales = saleService.convertToResponses(sales).stream()
            .filter(sale -> !sale.getSaleDate().toLocalDate().isBefore(startDate) && 
                           !sale.getSaleDate().toLocalDate().isAfter(endDate))
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
