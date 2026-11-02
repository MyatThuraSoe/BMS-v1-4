package com.bms.controller;

import com.bms.dto.request.GlAccountRequest;
import com.bms.dto.request.GlMappingRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.JournalEntryResponse;
import com.bms.entity.GlAccount;
import com.bms.entity.GlMapping;
import com.bms.service.GlService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/gl")
public class GlController {

    private final GlService glService;

    public GlController(GlService glService) {
        this.glService = glService;
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<GlAccount>>> accounts() {
        return ResponseEntity.ok(new ApiResponse<>(true, "GL accounts retrieved successfully",
                glService.listAccounts()));
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<GlAccount>> createAccount(
            @Valid @RequestBody GlAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "GL account created successfully",
                        glService.createAccount(request)));
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<GlAccount>> updateAccount(
            @PathVariable Long id, @Valid @RequestBody GlAccountRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(true, "GL account updated successfully",
                glService.updateAccount(id, request)));
    }

    @GetMapping("/mappings")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<GlMapping>>> mappings() {
        return ResponseEntity.ok(new ApiResponse<>(true, "GL mappings retrieved successfully",
                glService.listMappings()));
    }

    @PostMapping("/mappings")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<GlMapping>> upsertMapping(
            @Valid @RequestBody GlMappingRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(true, "GL mapping saved successfully",
                glService.upsertMapping(request)));
    }

    @DeleteMapping("/mappings/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteMapping(@PathVariable Long id) {
        glService.deleteMapping(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "GL mapping deleted successfully", null));
    }

    @GetMapping("/journal")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<JournalEntryResponse>>> journal(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Journal entries generated successfully",
                glService.journal(from, to)));
    }

    @GetMapping(value = "/journal/export", produces = "text/csv")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<String> exportJournal(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String filename = "journal-" + (from != null ? from : "start") + "-"
                + (to != null ? to : "today") + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(glService.journalCsv(from, to));
    }
}
