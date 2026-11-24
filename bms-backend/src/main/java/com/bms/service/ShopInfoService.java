package com.bms.service;

import com.bms.dto.response.ShopInfoResponse;
import com.bms.entity.ShopInfo;
import com.bms.repository.ShopInfoRepository;
import jakarta.transaction.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;

@Service
public class ShopInfoService {

    @Autowired
    private ShopTimeService shopTimeService;

    private final ShopInfoRepository shopInfoRepository;

    public ShopInfoService(ShopInfoRepository shopInfoRepository) {
        this.shopInfoRepository = shopInfoRepository;
    }

    @Transactional
    @Cacheable(cacheNames = "shopInfo")
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
                    "USD",
                    BigDecimal.ZERO,
                    false,
                    ShopInfo.DiscountType.PERCENTAGE.name(),
                    BigDecimal.ZERO,
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
                info.getCurrency() != null ? info.getCurrency() : "USD",
                info.getTaxPercentage(),
                info.getDiscountEnabled(),
                info.getDiscountType() != null ? info.getDiscountType().name()
                      : ShopInfo.DiscountType.PERCENTAGE.name(),
                info.getDiscountValue(),
                info.getLogoData() != null
        );
    }

    @Transactional
    @CacheEvict(cacheNames = "shopInfo", allEntries = true)
    public ShopInfoResponse upsertShopInfo(ShopInfoRequest req) {
        ShopInfo info = shopInfoRepository.findTopByOrderByIdAsc().orElseGet(ShopInfo::new);

        BigDecimal tax = req.getTaxPercentage() != null ? req.getTaxPercentage() : BigDecimal.ZERO;
        if (tax.compareTo(BigDecimal.ZERO) < 0 || tax.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new com.bms.exception.BusinessException("Tax percentage must be between 0 and 100");
        }
        BigDecimal discountValue = req.getDiscountValue() != null ? req.getDiscountValue() : BigDecimal.ZERO;
        if (discountValue.compareTo(BigDecimal.ZERO) < 0) {
            throw new com.bms.exception.BusinessException("Discount value cannot be negative");
        }
        info.setTaxPercentage(tax);
        info.setShopName(req.getShopName());
        info.setShopType(req.getShopType() != null ? req.getShopType() : ShopInfo.ShopType.OTHER);
        info.setAddress(req.getAddress());
        info.setPhone(req.getPhone());
        info.setEmail(req.getEmail());
        info.setCurrency(req.getCurrency() != null ? req.getCurrency() : "USD");
        info.setDiscountEnabled(Boolean.TRUE.equals(req.getDiscountEnabled()));
        ShopInfo.DiscountType discountType = ShopInfo.DiscountType.PERCENTAGE;
        if ("AMOUNT".equalsIgnoreCase(req.getDiscountType())) {
            discountType = ShopInfo.DiscountType.AMOUNT;
        } else if ("FIXED".equalsIgnoreCase(req.getDiscountType())) {
            discountType = ShopInfo.DiscountType.FIXED;
        }
        info.setDiscountType(discountType);
        if (discountType == ShopInfo.DiscountType.PERCENTAGE
                && discountValue.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new com.bms.exception.BusinessException("Percentage discount cannot exceed 100");
        }
        info.setDiscountValue(discountValue);

        ShopInfo saved = shopInfoRepository.save(info);
        return getShopInfo();
    }

    @Transactional
    @CacheEvict(cacheNames = "shopInfo", allEntries = true)
    public void uploadLogo(MultipartFile file) throws IOException {
        // Trust magic bytes, never the client Content-Type. Prevents storing (then
        // serving inline) HTML/JS payloads that would execute in the browser.
        String mime = com.bms.util.ImageValidationUtil.validateImage(file);
        ShopInfo info = shopInfoRepository.findTopByOrderByIdAsc().orElseGet(ShopInfo::new);
        info.setLogoData(file.getBytes());
        info.setLogoType(mime);
        shopInfoRepository.save(info);
    }

    @Transactional
    @CacheEvict(cacheNames = "shopInfo", allEntries = true)
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
        private String currency;
        private BigDecimal taxPercentage;
        private Boolean discountEnabled;
        private String discountType;
        private BigDecimal discountValue;

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

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public BigDecimal getTaxPercentage() {
            return taxPercentage;
        }

        public void setTaxPercentage(BigDecimal taxPercentage) {
            this.taxPercentage = taxPercentage;
        }

        public Boolean getDiscountEnabled() {
            return discountEnabled;
        }

        public void setDiscountEnabled(Boolean discountEnabled) {
            this.discountEnabled = discountEnabled;
        }

        public String getDiscountType() {
            return discountType;
        }

        public void setDiscountType(String discountType) {
            this.discountType = discountType;
        }

        public BigDecimal getDiscountValue() {
            return discountValue;
        }

        public void setDiscountValue(BigDecimal discountValue) {
            this.discountValue = discountValue;
        }
    }

    public record LogoPayload(byte[] data, String contentType) {
    }
}

