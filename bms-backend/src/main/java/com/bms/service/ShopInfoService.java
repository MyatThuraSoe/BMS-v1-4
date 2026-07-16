package com.bms.service;

import com.bms.dto.request.ShopInfoRequest;
import com.bms.dto.response.ShopInfoResponse;
import com.bms.entity.ShopInfo;
import com.bms.entity.ShopType;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.ShopInfoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@Transactional
public class ShopInfoService {

    @Autowired
    private ShopInfoRepository shopInfoRepository;

    @Transactional(readOnly = true)
    public ShopInfoResponse getShopInfo() {
        ShopInfo shopInfo = shopInfoRepository.findFirst().orElse(null);
        
        if (shopInfo == null) {
            // Return empty defaults instead of 404
            return new ShopInfoResponse();
        }
        
        return convertToResponse(shopInfo);
    }

    public ShopInfoResponse updateShopInfo(ShopInfoRequest request) {
        ShopInfo shopInfo = shopInfoRepository.findFirst().orElse(null);
        
        if (shopInfo == null) {
            shopInfo = new ShopInfo();
        }
        
        shopInfo.setShopName(request.getShopName());
        shopInfo.setShopType(ShopType.valueOf(request.getShopType()));
        shopInfo.setAddress(request.getAddress());
        shopInfo.setPhone(request.getPhone());
        shopInfo.setEmail(request.getEmail());
        
        ShopInfo saved = shopInfoRepository.save(shopInfo);
        return convertToResponse(saved);
    }

    public void uploadLogo(MultipartFile file) throws IOException {
        ShopInfo shopInfo = shopInfoRepository.findFirst().orElse(null);
        
        if (shopInfo == null) {
            shopInfo = new ShopInfo();
            shopInfo.setShopName("Default Shop");
            shopInfo.setShopType(ShopType.OTHER);
        }
        
        shopInfo.setLogoData(file.getBytes());
        shopInfo.setLogoType(getFileExtension(file.getOriginalFilename()));
        
        shopInfoRepository.save(shopInfo);
    }

    public byte[] getLogo() {
        ShopInfo shopInfo = shopInfoRepository.findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Shop info not found"));
        
        if (shopInfo.getLogoData() == null) {
            throw new ResourceNotFoundException("Logo not found");
        }
        
        return shopInfo.getLogoData();
    }

    public String getLogoType() {
        ShopInfo shopInfo = shopInfoRepository.findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Shop info not found"));
        
        return shopInfo.getLogoType();
    }

    public void deleteLogo() {
        ShopInfo shopInfo = shopInfoRepository.findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Shop info not found"));
        
        shopInfo.setLogoData(null);
        shopInfo.setLogoType(null);
        shopInfoRepository.save(shopInfo);
    }

    private ShopInfoResponse convertToResponse(ShopInfo shopInfo) {
        ShopInfoResponse response = new ShopInfoResponse();
        response.setId(shopInfo.getId());
        response.setShopName(shopInfo.getShopName());
        response.setShopType(shopInfo.getShopType().name());
        response.setAddress(shopInfo.getAddress());
        response.setPhone(shopInfo.getPhone());
        response.setEmail(shopInfo.getEmail());
        response.setHasLogo(shopInfo.getLogoData() != null && shopInfo.getLogoData().length > 0);
        response.setCreatedAt(shopInfo.getCreatedAt());
        response.setUpdatedAt(shopInfo.getUpdatedAt());
        return response;
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}
