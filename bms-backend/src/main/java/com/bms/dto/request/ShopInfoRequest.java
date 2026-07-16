package com.bms.dto.request;

public class ShopInfoRequest {
    private String shopName;
    private String shopType;
    private String address;
    private String phone;
    private String email;

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
}
