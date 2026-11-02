package com.bms.controller;

import com.bms.dto.request.LocationRequest;
import com.bms.dto.request.RescueStockRequest;
import com.bms.dto.request.StockTransferRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.entity.Location;
import com.bms.entity.StockTransfer;
import com.bms.service.LocationService;
import com.bms.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationService locationService;
    private final UserService userService;

    public LocationController(LocationService locationService, UserService userService) {
        this.locationService = locationService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<List<Location>>> list() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Locations retrieved successfully",
                locationService.list()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Location>> create(@Valid @RequestBody LocationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Location created successfully",
                        locationService.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Location>> update(@PathVariable Long id,
                                                        @Valid @RequestBody LocationRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Location updated successfully",
                locationService.update(id, request)));
    }

    @GetMapping("/{id}/stock")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> stock(@PathVariable Long id) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Location stock retrieved successfully",
                locationService.stockByLocation(id)));
    }

    @GetMapping("/put-away")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> putAway() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Put-away list retrieved successfully",
                locationService.putAwayList()));
    }

    @GetMapping("/bin-labels")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> binLabels(
            @RequestParam(defaultValue = "false") boolean includeUnassigned) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Bin labels retrieved successfully",
                locationService.binLabels(includeUnassigned)));
    }

    @GetMapping("/scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scan(@RequestParam String code) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Scan resolved successfully",
                locationService.scan(code)));
    }

    @PostMapping("/rescue")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rescue(
            @Valid @RequestBody RescueStockRequest request, Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(new ApiResponse<>(true, "Misplaced stock rescued successfully",
                locationService.rescue(request, userId)));
    }

    // ------------------------------------------------------------------
    // Transfers
    // ------------------------------------------------------------------

    @GetMapping("/transfers")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Page<StockTransfer>>> listTransfers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return ResponseEntity.ok(new ApiResponse<>(true, "Stock transfers retrieved successfully",
                locationService.listTransfers(pageable)));
    }

    @GetMapping("/transfers/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<StockTransfer>> getTransfer(@PathVariable Long id) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Stock transfer retrieved successfully",
                locationService.getTransfer(id)));
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockTransfer>> createTransfer(
            @Valid @RequestBody StockTransferRequest request, Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Stock transfer created successfully",
                        locationService.createTransfer(request, userId)));
    }

    @PostMapping("/transfers/{id}/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockTransfer>> startTransfer(
            @PathVariable Long id, Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(new ApiResponse<>(true, "Stock transfer dispatched",
                locationService.startTransfer(id, userId)));
    }

    @PostMapping("/transfers/{id}/receive")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockTransfer>> receiveTransfer(
            @PathVariable Long id, Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(new ApiResponse<>(true, "Stock transfer received",
                locationService.receiveTransfer(id, userId)));
    }

    @PostMapping("/transfers/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockTransfer>> completeTransfer(
            @PathVariable Long id, Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(new ApiResponse<>(true, "Stock transfer completed",
                locationService.completeTransfer(id, userId)));
    }

    @PostMapping("/transfers/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<StockTransfer>> cancelTransfer(
            @PathVariable Long id, Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(new ApiResponse<>(true, "Stock transfer cancelled",
                locationService.cancelTransfer(id, userId)));
    }
}
