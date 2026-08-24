package com.bms.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "shop_info")
public class ShopInfo {

    public enum ShopType {
        MINI_MART,
        GROCERY,
        PHARMACY,
        FURNITURE_SHOP,
        ELECTRONICS,
        CLOTHING,
        RESTAURANT,
        OTHER
    }

    public enum DiscountType {
        PERCENTAGE,
        // Admin-set flat amount subtracted from every sale automatically
        FIXED,
        // Cashier-entered amount per sale
        AMOUNT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shop_name", nullable = false)
    private String shopName;

    @Enumerated(EnumType.STRING)
    @Column(name = "shop_type", nullable = false, length = 50)
    private ShopType shopType;

    @Column(name = "address")
    private String address;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 100)
    private String email;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "logo_data", columnDefinition = "LONGBLOB")
    private byte[] logoData;

    @Column(name = "logo_type", length = 10)
    private String logoType;

    @Column(name = "currency", length = 10)
    private String currency = "USD";

    @Column(name = "tax_percentage", precision = 6, scale = 4)
    private BigDecimal taxPercentage = BigDecimal.ZERO;

    // Nullable on purpose: ddl-auto=update cannot add a NOT NULL column to a
    // table that already has rows (H2/MySQL reject it). getDiscountEnabled()
    // normalizes null -> false instead.
    @Column(name = "discount_enabled")
    private Boolean discountEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", length = 20)
    private DiscountType discountType = DiscountType.PERCENTAGE;

    // PERCENTAGE: percent off the subtotal. AMOUNT: default/fixed amount the
    // cashier may adjust per sale (capped at the subtotal server-side).
    @Column(name = "discount_value", precision = 10, scale = 2)
    private BigDecimal discountValue = BigDecimal.ZERO;

    @Version
    private Long version;

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

    public ShopType getShopType() {
        return shopType;
    }

    public void setShopType(ShopType shopType) {
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

    public byte[] getLogoData() {
        return logoData;
    }

    public void setLogoData(byte[] logoData) {
        this.logoData = logoData;
    }

    public String getLogoType() {
        return logoType;
    }

    public void setLogoType(String logoType) {
        this.logoType = logoType;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getTaxPercentage() {
        return taxPercentage != null ? taxPercentage : BigDecimal.ZERO;
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

    public DiscountType getDiscountType() {
        return discountType != null ? discountType : DiscountType.PERCENTAGE;
    }

    public void setDiscountType(DiscountType discountType) {
        this.discountType = discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue != null ? discountValue : BigDecimal.ZERO;
    }

    public void setDiscountValue(BigDecimal discountValue) {
        this.discountValue = discountValue;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}

