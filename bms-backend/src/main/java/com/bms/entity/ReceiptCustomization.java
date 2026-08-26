package com.bms.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "receipt_customizations")
public class ReceiptCustomization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "header_text", length = 255)
    private String headerText = "Thank you for shopping with us";

    @Column(name = "main_message", length = 255)
    private String mainMessage = "Please keep this receipt for your records.";

    @Column(name = "footer_text", length = 255)
    private String footerText = "Thank you for your business!";

    @Column(name = "paper_size", length = 10)
    private String paperSize = "58";

    @Column(name = "time_format", length = 10)
    private String timeFormat = "12";

    // --- Advanced design fields ---

    @Column(name = "logo_size")
    private Integer logoSize = 80;

    @Column(name = "show_logo")
    private Boolean showLogo = true;

    @Column(name = "show_shop_name")
    private Boolean showShopName = true;

    @Column(name = "show_address")
    private Boolean showAddress = true;

    @Column(name = "show_phone")
    private Boolean showPhone = true;

    @Column(name = "header_align", length = 10)
    private String headerAlign = "center";

    @Column(name = "font_size", length = 10)
    private String fontSize = "normal";

    @Column(name = "divider_style", length = 10)
    private String dividerStyle = "dashed";

    @Column(name = "bold_shop_name")
    private Boolean boldShopName = true;

    @Column(name = "show_qr_code")
    private Boolean showQRCode = false;

    @Column(name = "show_credit_info")
    private Boolean showCreditInfo = true;

    @Column(name = "show_tax")
    private Boolean showTax = true;

    @Column(name = "show_discount")
    private Boolean showDiscount = true;

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getHeaderText() { return headerText; }
    public void setHeaderText(String headerText) { this.headerText = headerText; }

    public String getMainMessage() { return mainMessage; }
    public void setMainMessage(String mainMessage) { this.mainMessage = mainMessage; }

    public String getFooterText() { return footerText; }
    public void setFooterText(String footerText) { this.footerText = footerText; }

    public String getPaperSize() { return paperSize; }
    public void setPaperSize(String paperSize) { this.paperSize = paperSize; }

    public String getTimeFormat() { return timeFormat; }
    public void setTimeFormat(String timeFormat) { this.timeFormat = timeFormat; }

    public Integer getLogoSize() { return logoSize; }
    public void setLogoSize(Integer logoSize) { this.logoSize = logoSize; }

    public Boolean getShowLogo() { return showLogo; }
    public void setShowLogo(Boolean showLogo) { this.showLogo = showLogo; }

    public Boolean getShowShopName() { return showShopName; }
    public void setShowShopName(Boolean showShopName) { this.showShopName = showShopName; }

    public Boolean getShowAddress() { return showAddress; }
    public void setShowAddress(Boolean showAddress) { this.showAddress = showAddress; }

    public Boolean getShowPhone() { return showPhone; }
    public void setShowPhone(Boolean showPhone) { this.showPhone = showPhone; }

    public String getHeaderAlign() { return headerAlign; }
    public void setHeaderAlign(String headerAlign) { this.headerAlign = headerAlign; }

    public String getFontSize() { return fontSize; }
    public void setFontSize(String fontSize) { this.fontSize = fontSize; }

    public String getDividerStyle() { return dividerStyle; }
    public void setDividerStyle(String dividerStyle) { this.dividerStyle = dividerStyle; }

    public Boolean getBoldShopName() { return boldShopName; }
    public void setBoldShopName(Boolean boldShopName) { this.boldShopName = boldShopName; }

    public Boolean getShowQRCode() { return showQRCode; }
    public void setShowQRCode(Boolean showQRCode) { this.showQRCode = showQRCode; }

    public Boolean getShowCreditInfo() { return showCreditInfo; }
    public void setShowCreditInfo(Boolean showCreditInfo) { this.showCreditInfo = showCreditInfo; }

    public Boolean getShowTax() { return showTax; }
    public void setShowTax(Boolean showTax) { this.showTax = showTax; }

    public Boolean getShowDiscount() { return showDiscount; }
    public void setShowDiscount(Boolean showDiscount) { this.showDiscount = showDiscount; }
}
