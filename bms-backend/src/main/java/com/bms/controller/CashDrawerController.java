package com.bms.controller;

import com.bms.dto.request.CloseDrawerSessionRequest;
import com.bms.dto.request.DrawerMovementRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.DrawerMovementResponse;
import com.bms.dto.response.DrawerSessionResponse;
import com.bms.service.CashDrawerSessionService;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/drawer-sessions")
public class CashDrawerController {

    @Autowired
    private CashDrawerSessionService drawerSessionService;

    @Autowired
    private UserService userService;

    private Long getUserId(Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails =
                (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        return userService.findByUsername(userDetails.getUsername()).getId();
    }

    private boolean isManagerOrAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")
                        || a.getAuthority().equals("ROLE_MANAGER"));
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<DrawerSessionResponse>>> getActiveSessions() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Active drawer sessions retrieved",
                drawerSessionService.getActiveSessions()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Page<DrawerSessionResponse>>> getSessionHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        LocalDateTime start = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime end = endDate != null ? endDate.atTime(LocalTime.MAX) : null;
        Pageable pageable = PageRequest.of(page, size, Sort.by("openedAt").descending());
        return ResponseEntity.ok(new ApiResponse<>(true, "Drawer session history retrieved",
                drawerSessionService.getSessionHistory(start, end, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<DrawerSessionResponse>> getSession(@PathVariable Long id) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Drawer session retrieved",
                drawerSessionService.getSessionResponse(id)));
    }

    @GetMapping("/{id}/movements")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<DrawerSessionResponse>> getMovements(@PathVariable Long id) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Drawer movements retrieved",
                drawerSessionService.getSessionResponse(id)));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<DrawerSessionResponse>> closeSession(
            @PathVariable Long id,
            @Valid @RequestBody CloseDrawerSessionRequest request,
            Authentication authentication) {
        Long userId = getUserId(authentication);
        DrawerSessionResponse closed = drawerSessionService.closeSession(
                id, request, userId, isManagerOrAdmin(authentication));
        return ResponseEntity.ok(new ApiResponse<>(true, "Drawer session closed", closed));
    }

    @PostMapping("/{id}/movements")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<DrawerMovementResponse>> performMovement(
            @PathVariable Long id,
            @Valid @RequestBody DrawerMovementRequest request,
            Authentication authentication) {
        Long userId = getUserId(authentication);
        DrawerMovementResponse movement = drawerSessionService.performOperation(
                id, request, userId, isManagerOrAdmin(authentication));
        return ResponseEntity.ok(new ApiResponse<>(true, "Drawer operation recorded", movement));
    }
}
