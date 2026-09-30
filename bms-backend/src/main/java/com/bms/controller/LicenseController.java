package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.license.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/license")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;

    @GetMapping("/machine-id")
    public ResponseEntity<ApiResponse<Map<String, String>>> machineId() {
        return ResponseEntity.ok(new ApiResponse<>(true, "ok",
                Map.of("machineId", licenseService.machineId())));
    }

    @PostMapping("/activate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activate(@RequestBody Map<String, String> body) {
        String reason = licenseService.activateWithReason(body.get("licenseKey"));
        boolean ok = reason == null;
        Map<String, Object> data = new HashMap<>();
        data.put("activated", ok);
        if (reason != null) data.put("reason", reason);
        return ResponseEntity.ok(new ApiResponse<>(ok, ok ? "License activated" : reason, data));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        Map<String, Object> data = new HashMap<>();
        data.put("licensed", licenseService.isLicensed());
        data.put("machineId", licenseService.machineId());

        Map<String, String> info = licenseService.licenseInfo();
        if (info != null) {
            data.put("customer", info.get("customer"));
            // ✅ NEW: Include the plan type
            data.put("plan", info.getOrDefault("plan", "lifetime"));
            
            String expiresAt = info.getOrDefault("expiresAt", "2099-12-31");
            data.put("expiresAt", expiresAt);
            
            // ✅ NEW: Calculate if it's expired and days left
            LocalDate exp = LocalDate.parse(expiresAt);
            data.put("expired", exp.isBefore(LocalDate.now()));
            data.put("daysLeft", java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), exp));
        } else {
            // ✅ NEW: Tell the UI WHY the install is unlicensed so it can direct
            // the user (install key / wrong machine / expired) instead of a 403.
            data.put("reason", licenseService.statusReason());
        }
        return ResponseEntity.ok(new ApiResponse<>(true, "License status", data));
    }
}