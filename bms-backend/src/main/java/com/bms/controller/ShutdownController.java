package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class ShutdownController {

    @Autowired
    private ConfigurableApplicationContext context;

    @PostMapping("/shutdown")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> shutdown() {
        // Start shutdown in a separate thread so the HTTP response can be sent first
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            context.close();
        }).start();

        return ResponseEntity.ok(new ApiResponse<>(true, "Server is shutting down. You can close this window.", null));
    }
}