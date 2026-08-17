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
        entity.setMainMessage(defaultIfBlank(request.getMainMessage(), "Please keep this receipt for your records."));
        entity.setFooterText(defaultIfBlank(request.getFooterText(), "Thank you for your business!"));
        entity.setPaperSize(normalizePaperSize(request.getPaperSize()));
        entity.setTimeFormat(normalizeTimeFormat(request.getTimeFormat()));
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

    public static class ReceiptCustomizationRequest {
        private String headerText;
        private String mainMessage;
        private String footerText;
        private String paperSize;
        private String timeFormat;

        public String getHeaderText() {
            return headerText;
        }

        public void setHeaderText(String headerText) {
            this.headerText = headerText;
        }

        public String getMainMessage() {
            return mainMessage;
        }

        public void setMainMessage(String mainMessage) {
            this.mainMessage = mainMessage;
        }

        public String getFooterText() {
            return footerText;
        }

        public void setFooterText(String footerText) {
            this.footerText = footerText;
        }

        public String getPaperSize() {
            return paperSize;
        }

        public void setPaperSize(String paperSize) {
            this.paperSize = paperSize;
        }

        public String getTimeFormat() {
            return timeFormat;
        }

        public void setTimeFormat(String timeFormat) {
            this.timeFormat = timeFormat;
        }
    }
}
