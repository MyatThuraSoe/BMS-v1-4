package com.bms.controller;

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
        html.append("@media print { @page { margin: 0; size: ").append(paperWidthMm).append("mm auto; } body { margin: 0; padding: 2px; } }");
        html.append("body { font-family: 'Courier New', monospace; font-size: 11px; width: ")
                .append(paperWidthMm).append("mm; margin: 0 auto; padding: 2px; }");
        html.append(".line { white-space: pre-wrap; word-wrap: break-word; margin: 0; line-height: 1.2; }");
        html.append("</style></head><body>");

        if (logoDataUri != null) {
            html.append("<div style='text-align: center; margin-bottom: 2px;'>");
            html.append("<img src='").append(logoDataUri).append("' style='max-width: 100%; max-height: 40px;' />");
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

            if (logoPayload != null && logoPayload.data() != null) {
                com.lowagie.text.Image logo = com.lowagie.text.Image.getInstance(logoPayload.data());
                logo.setAlignment(com.lowagie.text.Image.ALIGN_CENTER);
                logo.scaleToFit(120, 50);
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
            int logoBlock = (logoPayload != null && logoPayload.data() != null) ? 60 : 0;
            int height = padding * 2 + lineHeight * lines.size() + logoBlock;

            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2d = image.createGraphics();

            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect(0, 0, width, height);
            g2d.setColor(java.awt.Color.BLACK);
            g2d.setFont(new java.awt.Font("Courier New", java.awt.Font.PLAIN, 11));

            int y = padding;

            if (logoPayload != null && logoPayload.data() != null) {
                java.awt.image.BufferedImage logoImg = javax.imageio.ImageIO.read(new ByteArrayInputStream(logoPayload.data()));
                int targetW = 110;
                int targetH = 45;
                double aspect = (double) logoImg.getWidth() / (double) logoImg.getHeight();

                int drawW = targetW;
                int drawH = (int) Math.round(targetW / aspect);
                if (drawH > targetH) {
                    drawH = targetH;
                    drawW = (int) Math.round(targetH * aspect);
                }

                int xCenter = (width - drawW) / 2;
                g2d.drawImage(logoImg, xCenter, y, drawW, drawH, null);
                y += targetH + 5;
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

