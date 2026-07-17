package com.bms.controller;

import com.bms.dto.request.ProductCreateRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.ProductResponse;
import com.bms.entity.Product;
import com.bms.service.ProductService;
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

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    @Autowired
    private ProductService productService;

    @Autowired
    private com.bms.service.UserService userService;



    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Page<ProductResponse>>> getAllProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") String sortBy) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy));
        Page<ProductResponse> products = productService.getAllProducts(pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Products retrieved successfully", products));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<ProductResponse>> getProductById(@PathVariable Long id) {
        ProductResponse product = productService.getProductById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Product retrieved successfully", product));
    }

    @GetMapping("/sku/{sku}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<ProductResponse>> getProductBySku(@PathVariable String sku) {
        ProductResponse product = productService.getProductBySku(sku);
        return ResponseEntity.ok(new ApiResponse<>(true, "Product retrieved successfully", product));
    }

    @GetMapping("/barcode/{barcode}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<ProductResponse>> getProductByBarcode(@PathVariable String barcode) {
        ProductResponse product = productService.getProductByBarcode(barcode);
        return ResponseEntity.ok(new ApiResponse<>(true, "Product retrieved successfully", product));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Page<ProductResponse>>> searchProducts(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name"));
        Page<ProductResponse> products = productService.searchProducts(keyword, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Products searched successfully", products));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(@Valid @RequestBody ProductCreateRequest request) {
        Product product = productService.createProduct(request);
        ProductResponse response = convertToResponse(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true, "Product created successfully", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(@PathVariable Long id, @Valid @RequestBody ProductCreateRequest request) {
        Product product = productService.updateProduct(id, request);
        ProductResponse response = convertToResponse(product);
        return ResponseEntity.ok(new ApiResponse<>(true, "Product updated successfully", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Product deleted successfully", null));
    }

    @PostMapping("/{id}/image")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> uploadProductImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) throws IOException {
        productService.uploadProductImage(id, file);
        return ResponseEntity.ok(new ApiResponse<>(true, "Image uploaded successfully", null));
    }

    @GetMapping("/{id}/image")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<byte[]> getProductImage(@PathVariable Long id) {
        byte[] imageData = productService.getProductImage(id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"product_" + id + ".jpg\"")
                .body(imageData);
    }


    @DeleteMapping("/{id}/image")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteProductImage(@PathVariable Long id) {
        productService.deleteProductImage(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Image deleted successfully", null));
    }


    @PostMapping("/{id}/stock/adjust")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> adjustStock(
            @PathVariable Long id,
            @RequestParam Integer quantityChange,
            @RequestParam String reason,
            Authentication authentication) {
        // Extract authenticated user from SecurityContext
        org.springframework.security.core.userdetails.UserDetails userDetails = 
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        productService.adjustStock(userId, id, quantityChange, reason);
        return ResponseEntity.ok(new ApiResponse<>(true, "Stock adjusted successfully", null));
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<ProductResponse>>> getLowStockProducts(
            @RequestParam(defaultValue = "10") int threshold,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("stockQuantity"));
        Page<ProductResponse> products = productService.getLowStockProducts(threshold, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Low stock products retrieved successfully", products));
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
}
