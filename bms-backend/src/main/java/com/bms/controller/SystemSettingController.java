package com.bms.controller;

import com.bms.dto.request.SystemSettingRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.SystemSettingResponse;
import com.bms.service.SystemSettingService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settings")
public class SystemSettingController {

    @Autowired
    private SystemSettingService systemSettingService;

    @Autowired
    private com.bms.service.UserService userService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<SystemSettingResponse>>> getAllSettings() {
        List<SystemSettingResponse> settings = systemSettingService.getAllSettings();
        return ResponseEntity.ok(new ApiResponse<>(true, "Settings retrieved successfully", settings));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SystemSettingResponse>> getSettingById(@PathVariable Long id) {
        SystemSettingResponse setting = systemSettingService.getSettingById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Setting retrieved successfully", setting));
    }

    @GetMapping("/key/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<SystemSettingResponse>> getSettingByKey(@PathVariable String key) {
        SystemSettingResponse setting = systemSettingService.getSettingByKey(key);
        return ResponseEntity.ok(new ApiResponse<>(true, "Setting retrieved successfully", setting));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SystemSettingResponse>> createSetting(
            @Valid @RequestBody SystemSettingRequest request,
            Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails = 
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        SystemSettingResponse setting = systemSettingService.createSetting(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true, "Setting created successfully", setting));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SystemSettingResponse>> updateSetting(
            @PathVariable Long id,
            @Valid @RequestBody SystemSettingRequest request,
            Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails = 
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        SystemSettingResponse setting = systemSettingService.updateSetting(id, request, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Setting updated successfully", setting));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteSetting(
            @PathVariable Long id,
            Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails = 
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        systemSettingService.deleteSetting(id, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Setting deleted successfully", null));
    }
}
