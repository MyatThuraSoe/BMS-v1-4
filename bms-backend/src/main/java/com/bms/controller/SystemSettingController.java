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
    public ResponseEntity<ApiResponse<List<SystemSettingResponse>>> getAllSettings(
            @RequestParam(required = false, defaultValue = "false") boolean reveal,
            Authentication authentication) {
        if (!reveal) {
            return ResponseEntity.ok(new ApiResponse<>(true, "Settings retrieved successfully",
                    systemSettingService.getAllSettings()));
        }
        if (!isAdmin(authentication)) {
            return forbidden();
        }
        return ResponseEntity.ok(new ApiResponse<>(true, "Settings retrieved successfully",
                systemSettingService.getAllSettings(true, userIdOf(authentication))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SystemSettingResponse>> getSettingById(@PathVariable Long id) {
        SystemSettingResponse setting = systemSettingService.getSettingById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Setting retrieved successfully", setting));
    }

    @GetMapping("/key/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<SystemSettingResponse>> getSettingByKey(
            @PathVariable String key,
            @RequestParam(required = false, defaultValue = "false") boolean reveal,
            Authentication authentication) {
        if (!reveal) {
            return ResponseEntity.ok(new ApiResponse<>(true, "Setting retrieved successfully",
                    systemSettingService.getSettingByKey(key)));
        }
        // A reveal that is not allowed answers with 403 rather than with dots: a
        // page that asked for the text and got the mask back cannot tell whether
        // the feature works.
        if (!isAdmin(authentication)) {
            return forbidden();
        }
        return ResponseEntity.ok(new ApiResponse<>(true, "Setting retrieved successfully",
                systemSettingService.getSettingByKey(key, true, userIdOf(authentication))));
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private static <T> ResponseEntity<ApiResponse<T>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiResponse<>(false, "Only an administrator may read a stored secret", null));
    }

    private Long userIdOf(Authentication authentication) {
        return userService.findByUsername(authentication.getName()).getId();
    }

    @PutMapping("/key/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<SystemSettingResponse>> updateSettingByKey(
            @PathVariable String key,
            @RequestBody com.bms.dto.request.SystemSettingValueUpdateRequest request,
            Authentication authentication) {
        org.springframework.security.core.userdetails.UserDetails userDetails =
            (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        SystemSettingResponse setting = systemSettingService.updateSettingByKey(key, request.getSettingValue(), userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Setting updated successfully", setting));
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
