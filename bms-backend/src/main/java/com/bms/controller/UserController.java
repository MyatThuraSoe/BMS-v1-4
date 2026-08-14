package com.bms.controller;

import com.bms.dto.request.UserCreateRequest;
import com.bms.dto.request.UserUpdateRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.UserResponse;
import com.bms.dto.response.UserStatsDto;
import com.bms.entity.User;
import com.bms.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("en", "my", "ja", "th", "fr");

    @Autowired
    private UserService userService;

    @PatchMapping("/me/language")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserResponse>> updateMyLanguage(
            @RequestParam String lang,
            @org.springframework.security.core.annotation.AuthenticationPrincipal User user) {
        if (!SUPPORTED_LANGUAGES.contains(lang)) {
            return ResponseEntity.badRequest().build();
        }
        User updated = userService.updatePreferredLanguage(user.getUsername(), lang);
        UserResponse response = convertToResponse(updated);
        return ResponseEntity.ok(new ApiResponse<>(true, "Language updated successfully", response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable Long id) {
        User user = userService.findById(id);
        UserResponse response = convertToResponse(user);
        return ResponseEntity.ok(new ApiResponse<>(true, "User retrieved successfully", response));
    }

    @GetMapping("/{id}/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserStatsDto>> getUserStats(@PathVariable Long id) {
        UserStatsDto stats = userService.getUserSalesStats(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "User sales stats retrieved successfully", stats));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> createUser(@Valid @RequestBody UserCreateRequest request) {
        User user = userService.createUser(request);
        UserResponse response = convertToResponse(user);
        return ResponseEntity.ok(new ApiResponse<>(true, "User created successfully", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        User user = userService.updateUser(id, request);
        UserResponse response = convertToResponse(user);
        return ResponseEntity.ok(new ApiResponse<>(true, "User updated successfully", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "User deleted successfully"));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAllUsers() {

        List<UserResponse> users = userService.getAllUsers()
                .stream()
                .map(this::convertToResponse)
                .toList();

        return ResponseEntity.ok(
                new ApiResponse<>(
                        true,
                        "Users retrieved successfully",
                        users
                )
        );
    }

    private UserResponse convertToResponse(User user) {
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());
        response.setPhone(user.getPhone());
        response.setRoleName(user.getRole().getName().name());
        response.setIsActive(user.getIsActive());
        response.setPreferredLanguage(user.getPreferredLanguage());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        return response;
    }
}
