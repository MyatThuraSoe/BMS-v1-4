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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/shifts")
public class CashShiftController {

    @Autowired
    private CashShiftService cashShiftService;

    @Autowired
    private UserService userService;

    private Long getUserId(Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails =
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        return userService.findByUsername(userDetails.getUsername()).getId();
    }

    @PostMapping("/open")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CashShiftResponse>> openShift(
            @Valid @RequestBody OpenShiftRequest request,
            Authentication authentication) {
        Long cashierId = getUserId(authentication);
        CashShiftResponse shift = cashShiftService.openShift(cashierId, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Shift opened successfully", shift));
    }

    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CashShiftResponse>> getCurrentShift(Authentication authentication) {
        Long cashierId = getUserId(authentication);
        CashShiftResponse shift = cashShiftService.getCurrentShift(cashierId);
        return ResponseEntity.ok(new ApiResponse<>(true, shift != null ? "Current shift retrieved" : "No open shift", shift));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CashShiftResponse>> closeShift(
            @PathVariable Long id,
            @Valid @RequestBody CloseShiftRequest request,
            Authentication authentication) {
        Long userId = getUserId(authentication);
        boolean isManagerOrAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MANAGER"));
        CashShiftResponse shift = cashShiftService.getShiftById(id);
        if (!isManagerOrAdmin && !shift.getCashierId().equals(userId)) {
            throw new com.bms.exception.BusinessException("You can only close your own shift");
        }
        CashShiftResponse closed = cashShiftService.closeShift(id, request, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Shift closed successfully", closed));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<CashShiftResponse>>> getShiftHistory(
            @RequestParam(required = false) Long cashierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("openingTime").descending());
        Page<CashShiftResponse> shifts = cashShiftService.getShiftHistory(cashierId, startDate, endDate, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Shift history retrieved", shifts));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CashShiftResponse>> getShiftById(
            @PathVariable Long id,
            Authentication authentication) {
        Long callerId = getUserId(authentication);
        boolean isCashierRole = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_CASHIER"))
                && authentication.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MANAGER"));
        CashShiftResponse shift = cashShiftService.getShiftById(id);
        if (isCashierRole && !shift.getCashierId().equals(callerId)) {
            throw new com.bms.exception.BusinessException("You can only view your own shifts");
        }
        return ResponseEntity.ok(new ApiResponse<>(true, "Shift details retrieved", shift));
    }
}
