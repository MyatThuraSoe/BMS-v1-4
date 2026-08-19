package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.entity.ReceiptCustomization;
import com.bms.service.ReceiptCustomizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/receipt-customization")
public class ReceiptCustomizationController {

    private final ReceiptCustomizationService receiptCustomizationService;

    public ReceiptCustomizationController(ReceiptCustomizationService receiptCustomizationService) {
        this.receiptCustomizationService = receiptCustomizationService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<ReceiptCustomization>> getCustomization() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Receipt customization retrieved", receiptCustomizationService.getCustomization()));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<ApiResponse<ReceiptCustomization>> upsert(@RequestBody ReceiptCustomizationService.ReceiptCustomizationRequest request) {
        ReceiptCustomization response = receiptCustomizationService.upsertCustomization(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Receipt customization saved", response));
    }
}
