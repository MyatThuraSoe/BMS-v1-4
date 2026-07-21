package com.bms.controller;

import com.bms.dto.request.CloseShiftRequest;
import com.bms.dto.request.OpenShiftRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.CashShiftResponse;
import com.bms.service.CashShiftService;
import com.bms.service.UserService;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/shifts")
public class CashShiftController {

    @Autowired
    private CashShiftService cashShiftService;

    @Autowired
    private UserService userService;

    @PostMapping("/open")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CashShiftResponse>> openShift(
            @Valid @RequestBody OpenShiftRequest request,
            Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        Long cashierId = userService.findByUsername(userDetails.getUsername()).getId();
        CashShiftResponse shift = cashShiftService.openShift(request, cashierId);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new ApiResponse<>(true, "Shift opened successfully", shift));
    }

    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CashShiftResponse>> getCurrentShift(
            Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        Long cashierId = userService.findByUsername(userDetails.getUsername()).getId();
        CashShiftResponse shift = cashShiftService.getCurrentShift(cashierId);
        if (shift == null) {
            return ResponseEntity.ok(new ApiResponse<>(true, "No open shift", null));
        }
        return ResponseEntity.ok(new ApiResponse<>(true, "Current shift retrieved", shift));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CashShiftResponse>> closeShift(
            @PathVariable Long id,
            @Valid @RequestBody CloseShiftRequest request,
            Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        CashShiftResponse shift = cashShiftService.closeShift(id, request, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Shift closed successfully", shift));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<CashShiftResponse>>> getShiftHistory(
            @RequestParam(required = false) Long cashierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "openingTime") String sortBy) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).descending());
        LocalDateTime startDt = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDt = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;
        Page<CashShiftResponse> shifts = cashShiftService.getShiftHistory(cashierId, startDt, endDt, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Shift history retrieved", shifts));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CashShiftResponse>> getShiftById(
            @PathVariable Long id,
            Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        CashShiftResponse shift = cashShiftService.getShiftById(id, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Shift retrieved successfully", shift));
    }
}
