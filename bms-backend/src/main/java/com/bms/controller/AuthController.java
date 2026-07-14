package com.bms.controller;

import com.bms.dto.request.LoginRequest;
import com.bms.dto.request.RegisterRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.LoginResponse;
import com.bms.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

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
}
