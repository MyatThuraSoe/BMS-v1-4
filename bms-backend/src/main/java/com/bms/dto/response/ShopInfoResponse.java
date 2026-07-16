package com.bms.dto.response;

import java.time.LocalDateTime;

public class ShopInfoResponse {
    private Long id;
    private String shopName;
    private String shopType;
    private String address;
    private String phone;
    private String email;
    private boolean hasLogo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }

    public String getShopType() { return shopType; }
    public void setShopType(String shopType) { this.shopType = shopType; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public boolean isHasLogo() { return hasLogo; }
    public void setHasLogo(boolean hasLogo) { this.hasLogo = hasLogo; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
