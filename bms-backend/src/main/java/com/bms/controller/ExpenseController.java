package com.bms.controller;

import com.bms.dto.request.ExpenseRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.ExpenseResponse;
import com.bms.service.ExpenseService;
import com.bms.service.UserService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService expenseService;
    private final UserService userService;

    public ExpenseController(ExpenseService expenseService, UserService userService) {
        this.expenseService = expenseService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<ExpenseResponse>>> listExpenses(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Expenses retrieved", expenseService.listExpenses(category, startDate, endDate)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<ExpenseResponse>> getExpense(@PathVariable Long id) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Expense retrieved", expenseService.getExpense(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<ExpenseResponse>> createExpense(@Valid @RequestBody ExpenseRequest request, Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(new ApiResponse<>(true, "Expense created", expenseService.createExpense(request, userId)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<ExpenseResponse>> updateExpense(@PathVariable Long id, @Valid @RequestBody ExpenseRequest request, Authentication authentication) {
        Long userId = userService.findByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(new ApiResponse<>(true, "Expense updated", expenseService.updateExpense(id, request, userId)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteExpense(@PathVariable Long id) {
        expenseService.deleteExpense(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Expense deleted", null));
    }

    @PostMapping("/{id}/receipt-image")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> uploadReceiptImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) throws IOException {
        expenseService.uploadReceiptImage(id, file);
        return ResponseEntity.ok(new ApiResponse<>(true, "Receipt image uploaded", null));
    }

    @GetMapping("/{id}/receipt-image")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<byte[]> getReceiptImage(@PathVariable Long id) {
        byte[] imageData = expenseService.getReceiptImage(id);
        String imageType = expenseService.getReceiptImageType(id);
        MediaType mediaType = switch (imageType) {
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            default -> MediaType.IMAGE_JPEG;
        };
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"expense_" + id + "." + imageType + "\"")
                .body(imageData);
    }

    @DeleteMapping("/{id}/receipt-image")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteReceiptImage(@PathVariable Long id) {
        expenseService.deleteReceiptImage(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Receipt image deleted", null));
    }
}
