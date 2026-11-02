package com.bms.controller;

import com.bms.dto.request.PromotionRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.entity.Promotion;
import com.bms.service.PromotionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/promotions")
public class PromotionController {

    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<List<Promotion>>> list() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Promotions retrieved successfully",
                promotionService.getAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Promotion>> get(@PathVariable Long id) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Promotion retrieved successfully",
                promotionService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Promotion>> create(@Valid @RequestBody PromotionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Promotion created successfully",
                        promotionService.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Promotion>> update(@PathVariable Long id,
                                                         @Valid @RequestBody PromotionRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Promotion updated successfully",
                promotionService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        promotionService.delete(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Promotion deactivated successfully", null));
    }
}
