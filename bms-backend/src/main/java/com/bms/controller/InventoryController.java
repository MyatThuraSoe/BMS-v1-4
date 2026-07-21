package com.bms.controller;

import com.bms.dto.request.StockAdjustmentRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.ProductResponse;
import com.bms.dto.response.ReorderSuggestionDto;
import com.bms.entity.Product;
import com.bms.service.ProductService;
import com.bms.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.bms.entity.User;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    @Autowired
    private ProductService productService;

    @Autowired
    private InventoryService inventoryService;

    @GetMapping("/products")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Page<ProductResponse>>> getAllProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(required = false) Long categoryId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy));
        Page<ProductResponse> products = productService.getAllProducts(categoryId, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Products retrieved successfully", products));
    }

    @GetMapping("/products/low-stock")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<ProductResponse>>> getLowStockProducts(
            @RequestParam(defaultValue = "10") int threshold,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("stockQuantity"));
        Page<ProductResponse> products = productService.getLowStockProducts(threshold, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Low stock products retrieved successfully", products));
    }

    @GetMapping("/reorder-suggestions")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<ReorderSuggestionDto>>> getReorderSuggestions() {
        List<ReorderSuggestionDto> suggestions = inventoryService.getReorderSuggestions();
        return ResponseEntity.ok(new ApiResponse<>(true, "Reorder suggestions retrieved successfully", suggestions));
    }

    @PostMapping("/products/{id}/adjust")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> adjustStock(
            @PathVariable Long id,
            @Valid @RequestBody StockAdjustmentRequest request,
            Authentication authentication) {
        // Extract authenticated user from SecurityContext
        User currentUser = (User) authentication.getPrincipal();
        Long userId = currentUser.getId();
        productService.adjustStock(userId, id, request.getQuantityChange(), request.getReason());
        return ResponseEntity.ok(new ApiResponse<>(true, "Stock adjusted successfully", null));
    }

    private ProductResponse convertToResponse(Product product) {
        ProductResponse response = new ProductResponse();
        response.setId(product.getId());
        response.setSku(product.getSku());
        response.setName(product.getName());
        response.setDescription(product.getDescription());
        response.setUnitPrice(product.getUnitPrice());
        response.setCostPrice(product.getCostPrice());
        response.setTaxRate(product.getTaxRate());
        response.setStockQuantity(product.getStockQuantity());
        response.setMinStockLevel(product.getMinStockLevel());
        response.setBarcode(product.getBarcode());
        response.setIsActive(product.getIsActive());
        response.setCreatedAt(product.getCreatedAt());
        response.setUpdatedAt(product.getUpdatedAt());

        if (product.getCategory() != null) {
            response.setCategoryId(product.getCategory().getId());
            response.setCategoryName(product.getCategory().getName());
        }

        return response;
    }

//    @GetMapping("/products/{id}/movements")
//    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
//    public ResponseEntity<ApiResponse<Page<StockMovementResponse>>> getStockMovements(
//            @PathVariable Long id,
//            @RequestParam(defaultValue = "0") int page,
//            @RequestParam(defaultValue = "20") int size) {
//        // fetch from StockMovementRepository, filtered by productId, paginated, newest first
//    }
}
