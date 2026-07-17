package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.ShopInfoResponse;
import com.bms.service.ShopInfoService;
import com.bms.service.ShopInfoService.ShopInfoRequest;
import com.bms.service.ShopInfoService.LogoPayload;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/shop-info")
public class ShopInfoController {

    private final ShopInfoService shopInfoService;

    public ShopInfoController(ShopInfoService shopInfoService) {
        this.shopInfoService = shopInfoService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<ShopInfoResponse>> getShopInfo() {
        ShopInfoResponse resp = shopInfoService.getShopInfo();
        return ResponseEntity.ok(new ApiResponse<>(true, "Shop info retrieved", resp));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<ApiResponse<ShopInfoResponse>> upsert(@RequestBody ShopInfoRequest request) {
        ShopInfoResponse resp = shopInfoService.upsertShopInfo(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Shop info updated", resp));
    }

    @PostMapping("/logo")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> uploadLogo(@RequestParam("file") MultipartFile file) throws IOException {
        shopInfoService.uploadLogo(file);
        return ResponseEntity.ok(new ApiResponse<>(true, "Logo uploaded", null));
    }

    @GetMapping("/logo")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<byte[]> getLogo() {
        LogoPayload payload = shopInfoService.getLogoBytesOrNull();
        if (payload == null) {
            return ResponseEntity.notFound().build();
        }

        String contentType = payload.contentType() != null ? payload.contentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=shop_logo")
                .body(payload.data());
    }

    @DeleteMapping("/logo")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteLogo() {
        shopInfoService.deleteLogo();
        return ResponseEntity.ok(new ApiResponse<>(true, "Logo deleted", null));
    }
}

