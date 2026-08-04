package com.bms.controller;

import com.bms.dto.request.ChangePasswordRequest;
import com.bms.dto.request.LoginRequest;
import com.bms.dto.request.RegisterRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.LoginResponse;
import com.bms.service.AuthService;
import com.bms.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest loginRequest) {
        LoginResponse response = authService.authenticateUser(loginRequest);
        return ResponseEntity.ok(new ApiResponse<>(true, "Login successful", response));
    }

    @PostMapping("/register-first-admin")
    public ResponseEntity<ApiResponse<String>> registerFirstAdmin(@Valid @RequestBody RegisterRequest request) {
        authService.registerFirstAdmin(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "First admin user registered successfully", null));
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> changeOwnPassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        userService.changeOwnPassword(userDetails.getUsername(), request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Password changed successfully", null));
    }
}
