package com.bms.controller;

import com.bms.dto.order.OrderCancelRequest;
import com.bms.dto.order.OrderConvertRequest;
import com.bms.dto.order.OrderCreateRequest;
import com.bms.dto.order.OrderResponse;
import com.bms.dto.response.ApiResponse;
import com.bms.service.OrderService;
import com.bms.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<Page<OrderResponse>>> getOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String orderNumber,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Orders retrieved successfully",
                orderService.getOrders(status, startDate, endDate, customerId, orderNumber, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(new ApiResponse<>(true, "Order retrieved successfully", orderService.getOrderById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Order created successfully", orderService.createOrder(request, userId)));
    }

    @PostMapping("/{id}/convert")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<OrderResponse>> convertOrder(
            @PathVariable Long id,
            @RequestBody(required = false) OrderConvertRequest request,
            Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        OrderConvertRequest body = request != null ? request : new OrderConvertRequest();
        // Credit conversion is STRICTLY limited to ADMIN/MANAGER at the API level,
        // mirroring the restriction on direct credit sales in SaleController.
        boolean creditRequested = body.getPaymentMethod() != null
                && "CREDIT".equalsIgnoreCase(body.getPaymentMethod().trim());
        if (creditRequested && !hasManagerAuthority(authentication)) {
            throw new org.springframework.security.access.AccessDeniedException("Credit sales are restricted to managers");
        }
        return ResponseEntity.ok(new ApiResponse<>(true, "Order converted successfully", orderService.convertOrder(id, body, userId)));
    }

    private boolean hasManagerAuthority(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_MANAGER".equals(a.getAuthority()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable Long id,
            @RequestBody(required = false) OrderCancelRequest request,
            Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        Long userId = userService.findByUsername(userDetails.getUsername()).getId();
        OrderCancelRequest body = request != null ? request : new OrderCancelRequest();
        return ResponseEntity.ok(new ApiResponse<>(true, "Order cancelled successfully", orderService.cancelOrder(id, body, userId)));
    }
}