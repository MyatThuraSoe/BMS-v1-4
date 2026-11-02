package com.bms.controller;

import com.bms.dto.request.CountSheetCreateRequest;
import com.bms.dto.request.CountSubmitRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.CountSheetResponse;
import com.bms.entity.CountSheet;
import com.bms.exception.BusinessException;
import com.bms.service.StockCountService;
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
@RequestMapping("/api/stock-counts")
public class StockCountController {

    private final StockCountService stockCountService;
    private final UserService userService;

    public StockCountController(StockCountService stockCountService, UserService userService) {
        this.stockCountService = stockCountService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Page<CountSheetResponse>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        CountSheet.CountStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = CountSheet.CountStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("Invalid count sheet status: " + status);
            }
        }
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return ResponseEntity.ok(new ApiResponse<>(true, "Count sheets retrieved successfully",
                stockCountService.list(statusEnum, pageable)));
    }

    @GetMapping("/reason-codes")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<List<String>>> reasonCodes() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Reason codes retrieved successfully",
                StockCountService.REASON_CODES));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CountSheetResponse>> get(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean blind) {
        CountSheetResponse response = blind
                ? stockCountService.getBlind(id) : stockCountService.get(id, false);
        return ResponseEntity.ok(new ApiResponse<>(true, "Count sheet retrieved successfully", response));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<CountSheetResponse>> create(
            @Valid @RequestBody CountSheetCreateRequest request, Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Count sheet created successfully",
                        stockCountService.createManual(request, userId)));
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<CountSheet>>> generate() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Due count sheets generated successfully",
                stockCountService.generateDueSheets()));
    }

    @PostMapping("/refresh-abc")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshAbc() {
        int changed = stockCountService.refreshAbcClasses();
        return ResponseEntity.ok(new ApiResponse<>(true, "ABC classes refreshed successfully",
                Map.of("productsReclassified", changed)));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CountSheetResponse>> start(@PathVariable Long id) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Count sheet started successfully",
                stockCountService.start(id)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CountSheetResponse>> submit(
            @PathVariable Long id,
            @Valid @RequestBody CountSubmitRequest request,
            Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(new ApiResponse<>(true, "Count sheet submitted successfully",
                stockCountService.submit(id, request, userId)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<CountSheetResponse>> approve(
            @PathVariable Long id, Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(new ApiResponse<>(true, "Count sheet approved and stock adjusted",
                stockCountService.approve(id, userId)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<CountSheetResponse>> reject(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(new ApiResponse<>(true, "Count sheet rejected",
                stockCountService.reject(id, userId, reason)));
    }
}
