package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.service.CounterPrintService;
import com.bms.service.PrintAgentAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Counter (server-side) printing: lets ANY device — browser, phone, tablet,
 * or the desktop app — fire a receipt at the printer attached to the LumiPOS
 * server computer. No per-device installs required.
 *
 * Firing a print is for any signed-in role that may print. Taking a job off the
 * queue and reporting what happened to it is not: that is the print agent's work,
 * and {@link PrintAgentAccess} is what keeps everyone else out of it.
 */
@RestController
@RequestMapping("/api/counter-print")
public class CounterPrintController {

    private final CounterPrintService counterPrintService;
    private final PrintAgentAccess printAgentAccess;

    public CounterPrintController(CounterPrintService counterPrintService,
                                  PrintAgentAccess printAgentAccess) {
        this.counterPrintService = counterPrintService;
        this.printAgentAccess = printAgentAccess;
    }

    /** List printers installed on the server computer (default first). */
    @GetMapping("/printers")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listPrinters() {
        return ResponseEntity.ok(new ApiResponse<>(true, "Printers retrieved", counterPrintService.listPrinters()));
    }

    /** Admin config: which server printer is the counter printer ("" = default). */
    @GetMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfig() {
        var info = counterPrintService.listPrinters();
        return ResponseEntity.ok(new ApiResponse<>(true, "Config retrieved", Map.of(
                "printerName", info.getOrDefault("configured", ""),
                "default", info.getOrDefault("default", ""),
                "printers", info.getOrDefault("printers", java.util.List.of())
        )));
    }

    @PutMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> saveConfig(@RequestBody Map<String, String> body) {
        String printerName = body.getOrDefault("printerName", "");
        counterPrintService.saveConfiguredPrinter(printerName);
        return ResponseEntity.ok(new ApiResponse<>(true, "Counter printer saved", printerName));
    }

    /** Print a short test page so admins can verify wiring in seconds. */
    @PostMapping("/test")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<String>> testPrint(@RequestBody(required = false) Map<String, String> body) {
        counterPrintService.printTestPage(body != null ? body.get("printerName") : null);
        return ResponseEntity.ok(new ApiResponse<>(true, "Test page sent to the counter printer", null));
    }

    /** Queue a receipt for the Electron client on the main POS device. */
    @PostMapping("/receipt/{invoiceNumber}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Map<String, String>>> printReceipt(@PathVariable String invoiceNumber) {
        return ResponseEntity.accepted().body(new ApiResponse<>(true,
                "Receipt queued for the main POS printer", counterPrintService.enqueueReceipt(invoiceNumber)));
    }

    /** Poll endpoint used only by the print agent, so only this server may call it. */
    @GetMapping("/receipt-jobs/next")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Map<String, String>>> claimNextReceipt(HttpServletRequest httpRequest) {
        printAgentAccess.assertMayUseQueue(httpRequest);
        return ResponseEntity.ok(new ApiResponse<>(true, "Print job retrieved", counterPrintService.claimNextReceipt()));
    }

    /** Acknowledge the result after the print agent printed the receipt. */
    @PostMapping("/receipt-jobs/{jobId}/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Void>> completeReceipt(@PathVariable String jobId,
                                                             @RequestBody Map<String, Boolean> body,
                                                             HttpServletRequest httpRequest) {
        printAgentAccess.assertMayUseQueue(httpRequest);
        counterPrintService.completeReceipt(jobId, Boolean.TRUE.equals(body.get("success")));
        return ResponseEntity.ok(new ApiResponse<>(true, "Print job completed", null));
    }

    /** What the POS shows as "printing" or "failed" for a receipt it queued itself. */
    @GetMapping("/receipt-jobs/{jobId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Map<String, String>>> jobStatus(@PathVariable String jobId) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Print job status retrieved",
                counterPrintService.jobStatus(jobId)));
    }
}
