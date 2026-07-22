package com.bms.controller;

import com.bms.dto.request.SupplierCreateRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.SupplierResponse;
import com.bms.entity.Supplier;
import com.bms.service.SupplierService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    @Autowired
    private SupplierService supplierService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<SupplierResponse>>> getAllSuppliers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") String sortBy) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy));
        Page<SupplierResponse> suppliers = supplierService.getAllSuppliers(pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Suppliers retrieved successfully", suppliers));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<SupplierResponse>> getSupplierById(@PathVariable Long id) {
        SupplierResponse supplier = supplierService.getSupplierById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Supplier retrieved successfully", supplier));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<SupplierResponse>>> searchSuppliers(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name"));
        Page<SupplierResponse> suppliers = supplierService.searchSuppliers(keyword, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Suppliers searched successfully", suppliers));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<SupplierResponse>> createSupplier(@Valid @RequestBody SupplierCreateRequest request) {
        Supplier supplier = supplierService.createSupplier(request);
        SupplierResponse response = convertToResponse(supplier);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true, "Supplier created successfully", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<SupplierResponse>> updateSupplier(@PathVariable Long id, @Valid @RequestBody SupplierCreateRequest request) {
        Supplier supplier = supplierService.updateSupplier(id, request);
        SupplierResponse response = convertToResponse(supplier);
        return ResponseEntity.ok(new ApiResponse<>(true, "Supplier updated successfully", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteSupplier(@PathVariable Long id) {
        supplierService.deleteSupplier(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Supplier deleted successfully", null));
    }

    private SupplierResponse convertToResponse(Supplier supplier) {
        SupplierResponse response = new SupplierResponse();
        response.setId(supplier.getId());
        response.setName(supplier.getName());
        response.setContactPerson(supplier.getContactPerson());
        response.setEmail(supplier.getEmail());
        response.setPhone(supplier.getPhone());
        response.setAddress(supplier.getAddress());
        response.setTaxId(supplier.getTaxId());
        response.setPaymentTerms(supplier.getPaymentTerms());
        response.setNotes(supplier.getNotes());
        response.setIsActive(supplier.getIsActive());
        response.setCreatedAt(supplier.getCreatedAt());
        response.setUpdatedAt(supplier.getUpdatedAt());
        return response;
    }
}
