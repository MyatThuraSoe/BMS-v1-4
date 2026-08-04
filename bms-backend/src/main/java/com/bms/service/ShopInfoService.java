package com.bms.service;

import com.bms.dto.response.ShopInfoResponse;
import com.bms.entity.ShopInfo;
import com.bms.repository.ShopInfoRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;

@Service
public class ShopInfoService {

    private final ShopInfoRepository shopInfoRepository;

    public ShopInfoService(ShopInfoRepository shopInfoRepository) {
        this.shopInfoRepository = shopInfoRepository;
    }

    @Transactional
    public ShopInfoResponse getShopInfo() {
        Optional<ShopInfo> maybe = shopInfoRepository.findTopByOrderByIdAsc();
        if (maybe.isEmpty()) {
            return new ShopInfoResponse(
                    null,
                    "",
                    ShopInfo.ShopType.OTHER.name(),
                    "",
                    "",
                    "",
                    false
            );
        }

        ShopInfo info = maybe.get();
        return new ShopInfoResponse(
                info.getId(),
                info.getShopName(),
                info.getShopType() != null ? info.getShopType().name() : ShopInfo.ShopType.OTHER.name(),
                info.getAddress(),
                info.getPhone(),
                info.getEmail(),
                info.getLogoData() != null
        );
    }

    @Transactional
    public ShopInfoResponse upsertShopInfo(ShopInfoRequest req) {
        ShopInfo info = shopInfoRepository.findTopByOrderByIdAsc().orElseGet(ShopInfo::new);

        info.setShopName(req.getShopName());
        info.setShopType(req.getShopType() != null ? req.getShopType() : ShopInfo.ShopType.OTHER);
        info.setAddress(req.getAddress());
        info.setPhone(req.getPhone());
        info.setEmail(req.getEmail());

        ShopInfo saved = shopInfoRepository.save(info);
        return getShopInfo();
    }

    @Transactional
    public void uploadLogo(MultipartFile file) throws IOException {
        ShopInfo info = shopInfoRepository.findTopByOrderByIdAsc().orElseGet(ShopInfo::new);
        info.setLogoData(file.getBytes());
        info.setLogoType(file.getContentType());
        shopInfoRepository.save(info);
    }

    @Transactional
    public void deleteLogo() {
        ShopInfo info = shopInfoRepository.findTopByOrderByIdAsc().orElse(null);
        if (info == null) return;
        info.setLogoData(null);
        info.setLogoType(null);
        shopInfoRepository.save(info);
    }

    @Transactional
    public LogoPayload getLogoBytesOrNull() {
        ShopInfo info = shopInfoRepository.findTopByOrderByIdAsc().orElse(null);
        if (info == null || info.getLogoData() == null) return null;
        return new LogoPayload(info.getLogoData(), info.getLogoType());
    }

    // Request object kept inside service package to avoid extra DTO overhead
    public static class ShopInfoRequest {
        private String shopName;
        private ShopInfo.ShopType shopType;
        private String address;
        private String phone;
        private String email;

        public String getShopName() {
            return shopName;
        }

        public void setShopName(String shopName) {
            this.shopName = shopName;
        }

        public ShopInfo.ShopType getShopType() {
            return shopType;
        }

        public void setShopType(ShopInfo.ShopType shopType) {
            this.shopType = shopType;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    public record LogoPayload(byte[] data, String contentType) {
    }
}

