package com.bms.dto.response;

import java.math.BigDecimal;

public class ShopInfoResponse {

    private Long id;
    private String shopName;
    private String shopType;
    private String address;
    private String phone;
    private String email;
    private String currency;
    private BigDecimal taxPercentage;
    private Boolean discountEnabled;
    private String discountType;
    private BigDecimal discountValue;
    private boolean hasLogo;

    public ShopInfoResponse() {
    }

    public ShopInfoResponse(Long id,
                             String shopName,
                             String shopType,
                             String address,
                             String phone,
                             String email,
                             String currency,
                             BigDecimal taxPercentage,
                             Boolean discountEnabled,
                             String discountType,
                             BigDecimal discountValue,
                             boolean hasLogo) {
        this.id = id;
        this.shopName = shopName;
        this.shopType = shopType;
        this.address = address;
        this.phone = phone;
        this.email = email;
        this.currency = currency;
        this.taxPercentage = taxPercentage;
        this.discountEnabled = discountEnabled != null ? discountEnabled : false;
        this.discountType = discountType != null ? discountType : "PERCENTAGE";
        this.discountValue = discountValue;
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
        return discountEnabled != null ? discountEnabled : false;
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

    public boolean isHasLogo() {
        return hasLogo;
    }

    public void setHasLogo(boolean hasLogo) {
        this.hasLogo = hasLogo;
    }
}

