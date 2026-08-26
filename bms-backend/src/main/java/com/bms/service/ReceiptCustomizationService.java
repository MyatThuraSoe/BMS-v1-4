package com.bms.service;

import com.bms.entity.ReceiptCustomization;
import com.bms.repository.ReceiptCustomizationRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ReceiptCustomizationService {

    private final ReceiptCustomizationRepository receiptCustomizationRepository;

    public ReceiptCustomizationService(ReceiptCustomizationRepository receiptCustomizationRepository) {
        this.receiptCustomizationRepository = receiptCustomizationRepository;
    }

    @Transactional
    public ReceiptCustomization getCustomization() {
        return receiptCustomizationRepository.findTopByOrderByIdAsc()
            .orElseGet(() -> {
                ReceiptCustomization entity = new ReceiptCustomization();
                return receiptCustomizationRepository.save(entity);
            });
    }

    @Transactional
    public ReceiptCustomization upsertCustomization(ReceiptCustomizationRequest request) {
        ReceiptCustomization entity = receiptCustomizationRepository.findTopByOrderByIdAsc().orElseGet(ReceiptCustomization::new);
        entity.setHeaderText(trimToEmpty(request.getHeaderText()));
        entity.setMainMessage(trimToEmpty(request.getMainMessage()));
        entity.setFooterText(defaultIfBlank(request.getFooterText(), "Thank you for your business!"));
        entity.setPaperSize(normalizePaperSize(request.getPaperSize()));
        entity.setTimeFormat(normalizeTimeFormat(request.getTimeFormat()));

        // Advanced design fields
        if (request.getLogoSize() != null) {
            entity.setLogoSize(Math.max(20, Math.min(200, request.getLogoSize())));
        }
        if (request.getShowLogo() != null)    entity.setShowLogo(request.getShowLogo());
        if (request.getShowShopName() != null) entity.setShowShopName(request.getShowShopName());
        if (request.getShowAddress() != null) entity.setShowAddress(request.getShowAddress());
        if (request.getShowPhone() != null)   entity.setShowPhone(request.getShowPhone());
        if (request.getHeaderAlign() != null) entity.setHeaderAlign(normalizeAlign(request.getHeaderAlign()));
        if (request.getFontSize() != null)    entity.setFontSize(normalizeFontSize(request.getFontSize()));
        if (request.getDividerStyle() != null) entity.setDividerStyle(normalizeDividerStyle(request.getDividerStyle()));
        if (request.getBoldShopName() != null) entity.setBoldShopName(request.getBoldShopName());
        if (request.getShowQRCode() != null) entity.setShowQRCode(request.getShowQRCode());
        if (request.getShowCreditInfo() != null) entity.setShowCreditInfo(request.getShowCreditInfo());
        if (request.getShowTax() != null) entity.setShowTax(request.getShowTax());
        if (request.getShowDiscount() != null) entity.setShowDiscount(request.getShowDiscount());

        return receiptCustomizationRepository.save(entity);
    }

    private String normalizePaperSize(String paperSize) {
        String value = paperSize == null ? "58" : paperSize.trim();
        if (value.isBlank()) return "58";
        String digits = value.replaceAll("\\D", "");
        if (digits.isEmpty()) return "58";
        int parsed = Integer.parseInt(digits);
        if (parsed < 40) return "58";
        if (parsed > 120) return "100";
        return String.valueOf(parsed);
    }

    private String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeTimeFormat(String timeFormat) {
        if (timeFormat == null) return "12";
        String value = timeFormat.trim();
        if ("24".equals(value)) return "24";
        return "12";
    }

    private String normalizeAlign(String align) {
        if (align == null) return "center";
        return switch (align.toLowerCase().trim()) {
            case "left" -> "left";
            case "right" -> "right";
            default -> "center";
        };
    }

    private String normalizeFontSize(String fontSize) {
        if (fontSize == null) return "normal";
        return switch (fontSize.toLowerCase().trim()) {
            case "small" -> "small";
            case "large" -> "large";
            default -> "normal";
        };
    }

    private String normalizeDividerStyle(String style) {
        if (style == null) return "dashed";
        return switch (style.toLowerCase().trim()) {
            case "solid" -> "solid";
            case "dotted" -> "dotted";
            case "none" -> "none";
            default -> "dashed";
        };
    }

    public static class ReceiptCustomizationRequest {
        private String headerText;
        private String mainMessage;
        private String footerText;
        private String paperSize;
        private String timeFormat;
        // Advanced
        private Integer logoSize;
        private Boolean showLogo;
        private Boolean showShopName;
        private Boolean showAddress;
        private Boolean showPhone;
        private String headerAlign;
        private String fontSize;
        private String dividerStyle;
        private Boolean boldShopName;
        private Boolean showQRCode;
        private Boolean showCreditInfo;
        private Boolean showTax;
        private Boolean showDiscount;

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
}
