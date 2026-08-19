package com.bms.controller;

import com.bms.dto.request.ArPaymentRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.ArOutstandingItemResponse;
import com.bms.dto.response.ArPaymentResponse;
import com.bms.service.ArService;
import com.bms.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ar")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public class ArController {

    @Autowired
    private ArService arService;

    @Autowired
    private UserService userService;

    @GetMapping("/outstanding")
    public ResponseEntity<ApiResponse<Page<ArOutstandingItemResponse>>> getOutstanding(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("dueDate").ascending());
        Page<ArOutstandingItemResponse> result = arService.getOutstanding(keyword, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Outstanding AR retrieved successfully", result));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<Page<ArOutstandingItemResponse>>> getCustomerHistory(
            @PathVariable Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("saleDate").descending());
        Page<ArOutstandingItemResponse> result = arService.getCustomerHistory(customerId, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Customer AR history retrieved successfully", result));
    }

    @GetMapping("/{invoiceId}/payments")
    public ResponseEntity<ApiResponse<List<ArPaymentResponse>>> getInvoicePayments(
            @PathVariable Long invoiceId) {
        List<ArPaymentResponse> result = arService.getInvoicePayments(invoiceId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Invoice payment history retrieved successfully", result));
    }

    @PostMapping("/{invoiceId}/payment")
    public ResponseEntity<ApiResponse<ArPaymentResponse>> recordPayment(
            @PathVariable Long invoiceId,
            @Valid @RequestBody ArPaymentRequest request,
            Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails =
                (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        ArPaymentResponse result = arService.recordPayment(invoiceId, request, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Payment recorded successfully", result));
    }
}