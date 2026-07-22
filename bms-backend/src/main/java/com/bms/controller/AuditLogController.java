package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.entity.AuditLog;
import com.bms.service.AuditLogViewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    @Autowired
    private AuditLogViewService auditLogViewService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> getAllAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "timestamp") String sortBy) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).descending());
        Page<AuditLog> logs = auditLogViewService.getAllAuditLogs(pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Audit logs retrieved successfully", logs));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AuditLog>> getAuditLogById(@PathVariable Long id) {
        AuditLog log = auditLogViewService.getAuditLogById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Audit log retrieved successfully", log));
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> getAuditLogsByUserId(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        Page<AuditLog> logs = auditLogViewService.getAuditLogsByUserId(userId, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Audit logs retrieved successfully", logs));
    }

    @GetMapping("/action/{action}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> getAuditLogsByAction(
            @PathVariable String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        Page<AuditLog> logs = auditLogViewService.getAuditLogsByAction(action, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Audit logs retrieved successfully", logs));
    }

    @GetMapping("/date-range")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> getAuditLogsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        Page<AuditLog> logs = auditLogViewService.getAuditLogsByDateRange(startDate, endDate, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Audit logs retrieved successfully", logs));
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> searchAuditLogs(
            @RequestParam String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        Page<AuditLog> logs = auditLogViewService.searchAuditLogs(keyword, startDate, endDate, pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Audit logs searched successfully", logs));
    }
}
