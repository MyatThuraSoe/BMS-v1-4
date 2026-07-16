package com.bms.controller;

import com.bms.dto.request.ShopInfoRequest;
import com.bms.dto.response.ApiResponse;
import com.bms.dto.response.ShopInfoResponse;
import com.bms.service.ShopInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/shop-info")
public class ShopInfoController {

    @Autowired
    private ShopInfoService shopInfoService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<ShopInfoResponse>> getShopInfo() {
        ShopInfoResponse shopInfo = shopInfoService.getShopInfo();
        return ResponseEntity.ok(new ApiResponse<>(true, "Shop info retrieved successfully", shopInfo));
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ShopInfoResponse>> updateShopInfo(@RequestBody ShopInfoRequest request) {
        ShopInfoResponse shopInfo = shopInfoService.updateShopInfo(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Shop info updated successfully", shopInfo));
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> uploadLogo(@RequestParam("file") MultipartFile file) throws IOException {
        shopInfoService.uploadLogo(file);
        return ResponseEntity.ok(new ApiResponse<>(true, "Logo uploaded successfully", null));
    }

    @GetMapping("/logo")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<byte[]> getLogo() {
        byte[] logoData = shopInfoService.getLogo();
        String logoType = shopInfoService.getLogoType();
        MediaType mediaType = getMediaType(logoType);
        
        return ResponseEntity.ok()
                .contentType(mediaType != null ? mediaType : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"shop_logo." + logoType + "\"")
                .body(logoData);
    }

    @DeleteMapping("/logo")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteLogo() {
        shopInfoService.deleteLogo();
        return ResponseEntity.ok(new ApiResponse<>(true, "Logo deleted successfully", null));
    }

    private MediaType getMediaType(String extension) {
        if (extension == null) return null;
        switch (extension.toLowerCase()) {
            case "jpg":
            case "jpeg":
                return MediaType.IMAGE_JPEG;
            case "png":
                return MediaType.IMAGE_PNG;
            case "gif":
                return MediaType.IMAGE_GIF;
            case "webp":
                return new MediaType("image", "webp");
            default:
                return null;
        }
    }
}
