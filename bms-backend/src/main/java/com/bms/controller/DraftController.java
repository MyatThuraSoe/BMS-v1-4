package com.bms.controller;

import com.bms.dto.draft.DraftCreateRequest;
import com.bms.dto.draft.DraftResponse;
import com.bms.dto.response.ApiResponse;
import com.bms.service.DraftService;
import com.bms.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/drafts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
public class DraftController {
    private final DraftService draftService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DraftResponse>>> getAll(Authentication authentication) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Drafts retrieved successfully",
                draftService.getAll(userId(authentication))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DraftResponse>> getById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Draft retrieved successfully",
                draftService.getById(id, userId(authentication))));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DraftResponse>> create(@Valid @RequestBody DraftCreateRequest request,
                                                              Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(true, "Draft saved successfully",
                draftService.create(request, userId(authentication))));
    }

        @PutMapping("/{id}")
        public ResponseEntity<ApiResponse<DraftResponse>> update(@PathVariable Long id,
                                      @Valid @RequestBody DraftCreateRequest request,
                                      Authentication authentication) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Draft updated successfully",
            draftService.update(id, request, userId(authentication))));
        }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, Authentication authentication) {
        draftService.delete(id, userId(authentication));
        return ResponseEntity.ok(new ApiResponse<>(true, "Draft deleted successfully", null));
    }

    private Long userId(Authentication authentication) {
        UserDetails details = (UserDetails) authentication.getPrincipal();
        return userService.findByUsername(details.getUsername()).getId();
    }
}