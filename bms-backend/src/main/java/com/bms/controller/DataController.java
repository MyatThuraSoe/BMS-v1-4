package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.service.DataExportService;
import com.bms.service.DataImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {

    private final DataExportService exportService;
    private final DataImportService importService;

    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportData() {
        byte[] json = exportService.exportAllAsJson();
        String filename = "lumipos-backup-" + LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @PostMapping("/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importData(
            @RequestBody Map<String, Object> backup,
            @RequestParam(defaultValue = "MERGE") String mode) {
        DataImportService.ImportMode importMode =
                DataImportService.ImportMode.valueOf(mode.toUpperCase());
        Map<String, Object> result = importService.importAll(backup, importMode);
        return ResponseEntity.ok(new ApiResponse<>(true, "Import completed", result));
    }
}