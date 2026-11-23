package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.entity.PeriodClose;
import com.bms.exception.BusinessException;
import com.bms.service.AccountingIntegrityService;
import com.bms.service.AccountingPeriodService;
import com.bms.service.LedgerHistoryService;
import com.bms.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounting")
@PreAuthorize("hasRole('ADMIN')")
public class AccountingController {

    private final LedgerHistoryService historyService;
    private final AccountingPeriodService periodService;
    private final UserService userService;
    private final AccountingIntegrityService accountingIntegrityService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.bms.service.InventoryHistoryService inventoryHistoryService;

    public AccountingController(LedgerHistoryService historyService, AccountingPeriodService periodService,
                                 UserService userService, AccountingIntegrityService accountingIntegrityService) {
        this.historyService = historyService;
        this.periodService = periodService;
        this.userService = userService;
        this.accountingIntegrityService = accountingIntegrityService;
    }

    @PostMapping("/profit-backfill")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfill(@RequestBody BackfillRequest request,
                                                                     Authentication authentication) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Profit backfill result",
                historyService.backfillProfit(request.backupConfirmed(), userId(authentication))));
    }

    @PostMapping("/return-settlement-repair")
    public ResponseEntity<ApiResponse<Map<String, Object>>> returnSettlementRepair(@RequestBody BackfillRequest request,
                                                                                   Authentication authentication) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Return settlement repair result",
                historyService.repairReturnSettlements(request.backupConfirmed(), userId(authentication))));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reconciliation(
            @RequestParam(required = false) String startPeriod, @RequestParam(required = false) String endPeriod) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Ledger reconciliation",
                historyService.reconcile(startPeriod != null ? month(startPeriod) : null,
                        endPeriod != null ? month(endPeriod) : null)));
    }

    @GetMapping("/cost-reconciliation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> costReconciliation() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Read-only inventory cost reconciliation", inventoryHistoryService.reconcile()));
    }

    @PostMapping("/inventory-backfill")
    public ResponseEntity<ApiResponse<Map<String, Object>>> inventoryBackfill(@RequestBody BackfillRequest request,
                                                                            Authentication authentication) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Inventory backfill result",
                inventoryHistoryService.backfill(request.backupConfirmed(), userId(authentication))));
    }

    @PostMapping("/ledger-cutover")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cutover(Authentication authentication) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Ledger reads activated",
                periodService.activateLedgerReads(userId(authentication))));
    }

    @PostMapping("/cost-repair")
    public ResponseEntity<ApiResponse<Map<String, Object>>> costRepair(@RequestBody BackfillRequest request,
                                                                       Authentication authentication) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Null cost repair result",
                accountingIntegrityService.repairNullCostLines(request.backupConfirmed(), userId(authentication))));
    }

    @GetMapping("/periods")
    public ResponseEntity<ApiResponse<List<PeriodClose>>> periods() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Closed accounting periods", periodService.list()));
    }

    @PostMapping("/periods/{period}/close")
    public ResponseEntity<ApiResponse<PeriodClose>> close(@PathVariable String period, Authentication authentication) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Accounting period closed",
                periodService.close(month(period), userId(authentication))));
    }

    @PostMapping("/periods/{period}/reopen")
    public ResponseEntity<ApiResponse<Void>> reopen(@PathVariable String period,
                                                    @Valid @RequestBody ReopenRequest request,
                                                    Authentication authentication) {
        periodService.reopen(month(period), request.reason(), userId(authentication));
        return ResponseEntity.ok(new ApiResponse<>(true, "Accounting period reopened", null));
    }

    private Long userId(Authentication authentication) {
        return userService.findByUsername(authentication.getName()).getId();
    }

    private static YearMonth month(String period) {
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException ex) {
            throw new BusinessException("Period must use YYYY-MM format");
        }
    }

    public record BackfillRequest(boolean backupConfirmed) {}
    public record ReopenRequest(@NotBlank String reason) {}
}
