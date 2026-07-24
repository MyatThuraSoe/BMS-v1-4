package com.bms.controller;

import com.bms.dto.request.PurchaseCreateRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.PurchaseResponse;
import com.bms.dto.response.SupplierStatsResponse;
import com.bms.dto.response.SupplierTopProductResponse;
import com.bms.service.PurchaseService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.bms.dto.request.PurchasePaymentStatusUpdateRequest;

import java.util.List;


@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private com.bms.service.UserService userService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<PurchaseResponse>>> getAllPurchases(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "purchaseDate") String sortBy) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).descending());
        Page<PurchaseResponse> purchases = purchaseService.getAllPurchases(pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Purchases retrieved successfully", purchases));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<PurchaseResponse>> getPurchaseById(@PathVariable Long id) {
        PurchaseResponse purchase = purchaseService.getPurchaseById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Purchase retrieved successfully", purchase));
    }

    @GetMapping("/number/{purchaseNumber}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<PurchaseResponse>> getPurchaseByNumber(@PathVariable String purchaseNumber) {
        PurchaseResponse purchase = purchaseService.getPurchaseByNumber(purchaseNumber);
        return ResponseEntity.ok(new ApiResponse<>(true, "Purchase retrieved successfully", purchase));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<PurchaseResponse>> createPurchase(
            @Valid @RequestBody PurchaseCreateRequest request,
            Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails = 
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        PurchaseResponse purchase = purchaseService.createPurchase(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true, "Purchase created successfully", purchase));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<PurchaseResponse>> updatePurchase(
            @PathVariable Long id,
            @Valid @RequestBody PurchaseCreateRequest request,
            Authentication authentication) {

        Long userId = userService
                .findByUsername(authentication.getName())
                .getId();

        PurchaseResponse purchase = purchaseService.updatePurchase(id, request, userId);

        return ResponseEntity.ok(
                new ApiResponse<>(true, "Purchase updated successfully", purchase)
        );
    }

    @PatchMapping("/{id}/payment-status")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<PurchaseResponse>> updatePaymentStatus(

            @PathVariable Long id,

            @Valid
            @RequestBody PurchasePaymentStatusUpdateRequest request,

            Authentication authentication
    ) {

        org.springframework.security.core.userdetails.UserDetails userDetails =
                (org.springframework.security.core.userdetails.UserDetails)
                        authentication.getPrincipal();

        Long userId = userService
                .findByUsername(userDetails.getUsername())
                .getId();

        PurchaseResponse purchase = purchaseService.updatePaymentStatus(
                id,
                request.getPaymentStatus(),
                userId
        );

        return ResponseEntity.ok(
                new ApiResponse<>(
                        true,
                        "Purchase payment status updated successfully",
                        purchase
                )
        );
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deletePurchase(@PathVariable Long id, Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails = 
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        purchaseService.deletePurchase(id, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Purchase deleted successfully", null));
    }

    @GetMapping("/supplier/{supplierId}/stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<SupplierStatsResponse>> getSupplierStats(@PathVariable Long supplierId) {
        SupplierStatsResponse stats = purchaseService.getSupplierStats(supplierId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Supplier stats retrieved successfully", stats));
    }

    @GetMapping("/supplier/{supplierId}/top-products")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<SupplierTopProductResponse>>> getSupplierTopProducts(@PathVariable Long supplierId) {
        List<SupplierTopProductResponse> topProducts = purchaseService.getSupplierTopProducts(supplierId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Top products retrieved successfully", topProducts));
    }

}
