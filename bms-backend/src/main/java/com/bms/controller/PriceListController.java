package com.bms.controller;

import com.bms.dto.request.PriceListRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.entity.PriceList;
import com.bms.service.PriceListService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/price-lists")
public class PriceListController {

    private final PriceListService priceListService;

    public PriceListController(PriceListService priceListService) {
        this.priceListService = priceListService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<List<PriceList>>> list() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Price lists retrieved successfully",
                priceListService.getAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<PriceList>> get(@PathVariable Long id) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Price list retrieved successfully",
                priceListService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<PriceList>> create(@Valid @RequestBody PriceListRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Price list created successfully",
                        priceListService.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<PriceList>> update(@PathVariable Long id,
                                                         @Valid @RequestBody PriceListRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Price list updated successfully",
                priceListService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        priceListService.delete(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Price list deactivated successfully", null));
    }
}
