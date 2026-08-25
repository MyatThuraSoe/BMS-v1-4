package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.service.CounterPrintService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Counter (server-side) printing: lets ANY device — browser, phone, tablet,
 * or the desktop app — fire a receipt at the printer attached to the LumiPOS
 * server computer. No per-device installs required.
 */
@RestController
@RequestMapping("/api/counter-print")
public class CounterPrintController {

    private final CounterPrintService counterPrintService;

    public CounterPrintController(CounterPrintService counterPrintService) {
        this.counterPrintService = counterPrintService;
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

    /** THE endpoint every device calls to print a receipt at the counter. */
    @PostMapping("/receipt/{invoiceNumber}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<String>> printReceipt(@PathVariable String invoiceNumber) {
        counterPrintService.printInvoice(invoiceNumber, null);
        return ResponseEntity.ok(new ApiResponse<>(true, "Receipt sent to counter printer", invoiceNumber));
    }
}
