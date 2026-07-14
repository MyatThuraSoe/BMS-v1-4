package com.bms.controller;

import com.bms.dto.request.CartVerifyRequest;
import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.CartVerifyResponse;
import com.bms.dto.response.SaleResponse;
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
            @RequestParam(defaultValue = "saleDate") String sortBy) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).descending());
        Page<SaleResponse> sales = saleService.getNonVoidedSales(pageable).map(saleService::convertToResponse);
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
}
