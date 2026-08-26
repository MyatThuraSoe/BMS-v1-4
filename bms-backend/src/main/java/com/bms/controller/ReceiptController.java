package com.bms.controller;

import com.bms.dto.receipt.ArPaymentReceiptDto;
import com.bms.dto.receipt.ReceiptDto;
import com.bms.dto.response.ApiResponse;
import com.bms.entity.ReceiptCustomization;
import com.bms.service.ReceiptCustomizationService;
import com.bms.service.ReceiptService;
import com.bms.service.ShopInfoService;
import com.bms.service.ShopInfoService.LogoPayload;
import com.bms.util.ReceiptLayoutBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;
    private final ShopInfoService shopInfoService;
    private final ReceiptCustomizationService receiptCustomizationService;

    public ReceiptController(ReceiptService receiptService, ShopInfoService shopInfoService, ReceiptCustomizationService receiptCustomizationService) {
        this.receiptService = receiptService;
        this.shopInfoService = shopInfoService;
        this.receiptCustomizationService = receiptCustomizationService;
    }

    @GetMapping("/invoice/{invoiceNumber}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<ReceiptDto>> getReceiptByInvoiceNumber(@PathVariable String invoiceNumber) {
        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(invoiceNumber);
        return ResponseEntity.ok(new ApiResponse<>(true, "Receipt retrieved successfully", receipt));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<ApiResponse<ReceiptDto>> getReceiptById(@PathVariable Long id) {
        ReceiptDto receipt = receiptService.getReceiptById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Receipt retrieved successfully", receipt));
    }

    // HTML / print view
    @GetMapping("/invoice/{invoiceNumber}/print")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<String> printReceiptHtml(@PathVariable String invoiceNumber) {
        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(invoiceNumber);
        var shopInfo = shopInfoService.getShopInfo();
        LogoPayload logoPayload = shopInfoService.getLogoBytesOrNull();
        ReceiptCustomization customization = receiptCustomizationService.getCustomization();

        String logoDataUri = null;
        if (logoPayload != null && logoPayload.data() != null) {
            String mime = logoPayload.contentType() != null ? logoPayload.contentType() : "image/png";
            logoDataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(logoPayload.data());
        }

        ReceiptLayoutBuilder builder = new ReceiptLayoutBuilder(receipt, shopInfo, customization);
        List<String> lines = builder.build();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>");
        int paperWidthMm = builder.getPaperWidthMm();
        String bodyFontSize = switch (builder.getFontSize()) {
            case "small" -> "10px";
            case "large" -> "13px";
            default -> "11px";
        };
        html.append("@media print { @page { margin: 0; size: ").append(paperWidthMm).append("mm auto; } body { margin: 0; padding: 2px; } }");
        html.append("body { font-family: 'Courier New', monospace; font-size: ").append(bodyFontSize).append("; width: ")
                .append(paperWidthMm).append("mm; margin: 0 auto; padding: 2px; }");
        html.append(".line { white-space: pre-wrap; word-wrap: break-word; margin: 0; line-height: 1.2; }");
        html.append("</style></head><body>");

        if (logoDataUri != null && builder.isShowLogo()) {
            String logoAlign = switch (builder.getHeaderAlign()) {
                case "left" -> "left";
                case "right" -> "right";
                default -> "center";
            };
            html.append("<div style='text-align: ").append(logoAlign).append("; margin-bottom: 2px;'>");
            html.append("<img src='").append(logoDataUri).append("' style='max-width: 100%; height: ")
                    .append(builder.getLogoSize()).append("px; object-fit: contain;' />");
            html.append("</div>");
        }

        for (String line : lines) {
            html.append("<div class='line'>").append(ReceiptLayoutBuilder.escapeHtml(line)).append("</div>");
        }

        html.append("</body></html>");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        headers.setContentDispositionFormData("inline", "receipt_" + receipt.getInvoiceNumber() + ".html");

        return ResponseEntity.ok().headers(headers).body(html.toString());
    }

    // PDF (OpenPDF)
    @GetMapping("/invoice/{invoiceNumber}/pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<byte[]> generateReceiptPdf(@PathVariable String invoiceNumber) {
        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(invoiceNumber);
        var shopInfo = shopInfoService.getShopInfo();
        ReceiptCustomization customization = receiptCustomizationService.getCustomization();
        LogoPayload logoPayload = shopInfoService.getLogoBytesOrNull();

        try {
            ReceiptLayoutBuilder builder = new ReceiptLayoutBuilder(receipt, shopInfo, customization);
            List<String> lines = builder.build();
            int paperWidthPt = (int) Math.round(builder.getPaperWidthMm() * 72.0 / 25.4);

            com.lowagie.text.Rectangle pageSize = new com.lowagie.text.Rectangle(paperWidthPt, com.lowagie.text.PageSize.A4.getHeight());
            com.lowagie.text.Document document = new com.lowagie.text.Document(pageSize, 20, 20, 20, 20);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            com.lowagie.text.pdf.PdfWriter.getInstance(document, baos);

            document.open();

            com.lowagie.text.Font normalFont = new com.lowagie.text.Font(com.lowagie.text.Font.COURIER, 10, com.lowagie.text.Font.NORMAL);
            com.lowagie.text.Font boldFont = new com.lowagie.text.Font(com.lowagie.text.Font.COURIER, 10, com.lowagie.text.Font.BOLD);

            if (logoPayload != null && logoPayload.data() != null && builder.isShowLogo()) {
                com.lowagie.text.Image logo = com.lowagie.text.Image.getInstance(logoPayload.data());
                logo.setAlignment(com.lowagie.text.Image.ALIGN_CENTER);
                logo.scaleToFit(140, builder.getLogoSize());
                logo.setSpacingAfter(5);
                document.add(logo);
            }

            for (String line : lines) {
                // Bold total line
                com.lowagie.text.Font font = line.trim().startsWith("TOTAL") ? boldFont : normalFont;
                document.add(new com.lowagie.text.Paragraph(line, font));
            }

            document.close();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "receipt_" + invoiceNumber + ".pdf");

            return ResponseEntity.ok().headers(headers).body(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF receipt", e);
        }
    }

    // PNG (Graphics2D)
    @GetMapping("/invoice/{invoiceNumber}/png")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<byte[]> generateReceiptPng(@PathVariable String invoiceNumber) {
        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(invoiceNumber);
        var shopInfo = shopInfoService.getShopInfo();
        ReceiptCustomization customization = receiptCustomizationService.getCustomization();
        LogoPayload logoPayload = shopInfoService.getLogoBytesOrNull();

        try {
            ReceiptLayoutBuilder builder = new ReceiptLayoutBuilder(receipt, shopInfo, customization);
            List<String> lines = builder.build();
            
            int width = (int) Math.round(builder.getPaperWidthMm() * 6.9);
            int lineHeight = 14;
            int padding = 10;
            boolean withLogo = logoPayload != null && logoPayload.data() != null && builder.isShowLogo();
            int logoSize = builder.getLogoSize();
            int logoTargetH = Math.max(30, (int) Math.round(logoSize * 0.6));
            int logoTargetW = Math.max(50, logoSize * 2);
            int logoBlock = withLogo ? logoTargetH + 5 : 0;
            int height = padding * 2 + lineHeight * lines.size() + logoBlock;

            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2d = image.createGraphics();

            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect(0, 0, width, height);
            g2d.setColor(java.awt.Color.BLACK);
            g2d.setFont(new java.awt.Font("Courier New", java.awt.Font.PLAIN, 11));

            int y = padding;

            if (withLogo) {
                java.awt.image.BufferedImage logoImg = javax.imageio.ImageIO.read(new ByteArrayInputStream(logoPayload.data()));
                double aspect = (double) logoImg.getWidth() / (double) logoImg.getHeight();

                int drawW = logoTargetW;
                int drawH = (int) Math.round(drawW / aspect);
                if (drawH > logoTargetH) {
                    drawH = logoTargetH;
                    drawW = (int) Math.round(drawH * aspect);
                }

                int xCenter;
                switch (builder.getHeaderAlign()) {
                    case "left" -> xCenter = padding;
                    case "right" -> xCenter = width - padding - drawW;
                    default -> xCenter = (width - drawW) / 2;
                }
                g2d.drawImage(logoImg, xCenter, y, drawW, drawH, null);
                y += logoTargetH + 5;
            }

            for (String line : lines) {
                y += lineHeight;
                g2d.drawString(line, padding, y);
            }

            g2d.dispose();

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", baos);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentDispositionFormData("attachment", "receipt_" + invoiceNumber + ".png");

            return ResponseEntity.ok().headers(headers).body(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PNG receipt", e);
        }
    }

    // AR payment receipt (payment made against a credit invoice)
    @GetMapping("/ar-payment/{paymentId}/print")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<String> printArPaymentReceiptHtml(@PathVariable Long paymentId) {
        ArPaymentReceiptDto payment = receiptService.getArPaymentReceipt(paymentId);
        var shopInfo = shopInfoService.getShopInfo();
        LogoPayload logoPayload = shopInfoService.getLogoBytesOrNull();
        ReceiptCustomization customization = receiptCustomizationService.getCustomization();

        String logoDataUri = null;
        if (logoPayload != null && logoPayload.data() != null) {
            String mime = logoPayload.contentType() != null ? logoPayload.contentType() : "image/png";
            logoDataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(logoPayload.data());
        }

        int paperWidthMm = paperWidthMm(customization.getPaperSize());
        String currency = shopInfo != null ? shopInfo.getCurrency() : "USD";
        int lineWidth = Math.max(16, (int) Math.round(paperWidthMm * (1.0 / 1.47)));

        List<String> lines = new ArrayList<>();

        boolean showShopName = customization.getShowShopName() == null || customization.getShowShopName();
        boolean showAddress  = customization.getShowAddress()  == null || customization.getShowAddress();
        boolean showPhone    = customization.getShowPhone()    == null || customization.getShowPhone();

        if (showShopName) {
            lines.add(centerLine(shopInfo != null ? shopInfo.getShopName() : "Shop", lineWidth));
        }
        if (showAddress && shopInfo != null && shopInfo.getAddress() != null && !shopInfo.getAddress().isEmpty()) {
            lines.add(centerLine(shopInfo.getAddress(), lineWidth));
        }
        if (showPhone && shopInfo != null && shopInfo.getPhone() != null && !shopInfo.getPhone().isEmpty()) {
            lines.add(centerLine(shopInfo.getPhone(), lineWidth));
        }

        lines.add("");
        lines.add(repeatChar("-", lineWidth));
        lines.add(centerLine("PAYMENT RECEIPT", lineWidth));
        lines.add(repeatChar("-", lineWidth));
        lines.add("Invoice No: " + payment.getInvoiceNumber());
        lines.add("Date: " + formatPaymentDateTime(payment.getPaymentDate(), customization.getTimeFormat()));
        if (payment.getCustomerName() != null && !payment.getCustomerName().isBlank()) {
            lines.add("Customer: " + payment.getCustomerName());
        }
        lines.add("Collected By: " + (payment.getRecordedByName() != null ? payment.getRecordedByName() : "-"));
        lines.add("");
        lines.add(repeatChar("-", lineWidth));
        lines.add(totalLine("Amount Paid", payment.getAmount(), lineWidth, currency));
        lines.add(totalLine("Remaining Credit", payment.getBalanceAfter(), lineWidth, currency));
        if (payment.getNotes() != null && !payment.getNotes().isBlank()) {
            lines.add(repeatChar("-", lineWidth));
            lines.add("Notes: " + payment.getNotes());
        }
        lines.add("");
        lines.add(repeatChar("-", lineWidth));
        lines.add(centerLine(customization.getFooterText(), lineWidth));
        lines.add("");
        lines.add("");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>");
        html.append("@media print { @page { margin: 0; size: ").append(paperWidthMm).append("mm auto; } body { margin: 0; padding: 2px; } }");
        html.append("body { font-family: 'Courier New', monospace; font-size: 11px; width: ")
                .append(paperWidthMm).append("mm; margin: 0 auto; padding: 2px; }");
        html.append(".line { white-space: pre-wrap; word-wrap: break-word; margin: 0; line-height: 1.2; }");
        html.append("</style></head><body>");

        boolean arShowLogo = customization.getShowLogo() == null || customization.getShowLogo();
        int arLogoSize = customization.getLogoSize() == null ? 80 : Math.max(20, Math.min(160, customization.getLogoSize()));
        if (logoDataUri != null && arShowLogo) {
            html.append("<div style='text-align: center; margin-bottom: 2px;'>");
            html.append("<img src='").append(logoDataUri).append("' style='max-width: 100%; height: ")
                    .append(arLogoSize).append("px; object-fit: contain;' />");
            html.append("</div>");
        }

        for (String line : lines) {
            html.append("<div class='line'>").append(ReceiptLayoutBuilder.escapeHtml(line)).append("</div>");
        }

        html.append("</body></html>");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        headers.setContentDispositionFormData("inline", "ar_payment_" + payment.getPaymentId() + ".html");

        return ResponseEntity.ok().headers(headers).body(html.toString());
    }

    private static String repeatChar(String ch, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String centerLine(String text, int lineWidth) {
        if (text == null || text.isEmpty()) return "";
        int padding = Math.max(0, (lineWidth - text.length()) / 2);
        return repeatChar(" ", padding) + text;
    }

    private static String totalLine(String label, BigDecimal amount, int lineWidth, String currency) {
        String price = fmt(amount, currency);
        String line = label + ":";
        int padding = Math.max(1, lineWidth - line.length() - price.length());
        return line + repeatChar(" ", padding) + price;
    }

    private static String formatPaymentDateTime(LocalDateTime dateTime, String timeFormat) {
        if (dateTime == null) return "";
        String datePart = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(dateTime);
        if ("24".equalsIgnoreCase(timeFormat)) {
            return datePart + " " + DateTimeFormatter.ofPattern("HH:mm").format(dateTime);
        }
        int hour = dateTime.getHour();
        int hour12 = hour % 12 == 0 ? 12 : hour % 12;
        String ampm = hour < 12 ? "am" : "pm";
        return datePart + " " + hour12 + ":" + String.format("%02d", dateTime.getMinute()) + ampm;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String fmt(BigDecimal amount, String currencyCode) {
        if (amount == null) amount = BigDecimal.ZERO;
        DecimalFormat df = new DecimalFormat("#,##0.00");

        if ("MMK".equalsIgnoreCase(currencyCode)) {
            return df.format(amount) + " Ks";
        }

        String code = currencyCode == null || currencyCode.isBlank() ? "USD" : currencyCode;
        String symbol;
        switch (code) {
            case "THB": symbol = "฿"; break;
            case "EUR": symbol = "€"; break;
            case "GBP": symbol = "£"; break;
            case "SGD": symbol = "S$"; break;
            case "INR": symbol = "₹"; break;
            default: symbol = "$"; break;
        }
        return symbol + df.format(amount);
    }

    private static int paperWidthMm(String paperSize) {
        if (paperSize != null && !paperSize.isBlank()) {
            String digits = paperSize.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    int mm = Integer.parseInt(digits);
                    if (mm >= 20 && mm <= 200) {
                        return mm;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 58;
    }
}

