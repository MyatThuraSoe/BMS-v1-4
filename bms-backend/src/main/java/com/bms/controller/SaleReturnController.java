package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.SaleReturnResponse;
import com.bms.service.SaleReturnService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sale-returns")
public class SaleReturnController {

    @Autowired
    private SaleReturnService saleReturnService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<SaleReturnResponse>>> getAllReturns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long saleId,
            @RequestParam(required = false) String invoice) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("returnDate").descending());
        Page<SaleReturnResponse> returns = saleReturnService.getAllReturns(saleId, invoice, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Sale returns retrieved successfully", returns));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<SaleReturnResponse>> getReturnById(@PathVariable Long id) {
        SaleReturnResponse saleReturn = saleReturnService.getReturnById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Sale return retrieved successfully", saleReturn));
    }
}
