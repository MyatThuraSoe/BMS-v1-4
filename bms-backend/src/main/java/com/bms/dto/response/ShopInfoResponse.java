package com.bms.dto.response;

public class ShopInfoResponse {

    private Long id;
    private String shopName;
    private String shopType;
    private String address;
    private String phone;
    private String email;
    private boolean hasLogo;

    public ShopInfoResponse() {
    }

    public ShopInfoResponse(Long id,
                             String shopName,
                             String shopType,
                             String address,
                             String phone,
                             String email,
                             boolean hasLogo) {
        this.id = id;
        this.shopName = shopName;
        this.shopType = shopType;
        this.address = address;
        this.phone = phone;
        this.email = email;
        this.hasLogo = hasLogo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public String getShopType() {
        return shopType;
    }

    public void setShopType(String shopType) {
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

    public boolean isHasLogo() {
        return hasLogo;
    }

    public void setHasLogo(boolean hasLogo) {
        this.hasLogo = hasLogo;
    }
}

