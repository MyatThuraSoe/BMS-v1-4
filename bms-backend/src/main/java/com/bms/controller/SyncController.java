package com.bms.controller;

import com.bms.dto.request.SyncCountSheetRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.CountSheetResponse;
import com.bms.service.StockCountService;
import com.bms.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Offline-first sync endpoints. Writes are idempotent on a client-generated
 * key so a retry after a network timeout never double-adjusts stock.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final StockCountService stockCountService;
    private final UserService userService;

    public SyncController(StockCountService stockCountService, UserService userService) {
        this.stockCountService = stockCountService;
        this.userService = userService;
    }

    @PostMapping("/count-sheet")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<CountSheetResponse>> syncCountSheet(
            @Valid @RequestBody SyncCountSheetRequest request,
            Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(new ApiResponse<>(true, "Count sheet synced successfully",
                stockCountService.sync(request, userId)));
    }
}
