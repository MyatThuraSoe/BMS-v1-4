package com.bms.controller;

import com.bms.dto.response.ApiResponse;
import com.bms.dto.receipt.ReceiptDto;
import com.bms.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/receipts")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptService receiptService;

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

    @GetMapping("/invoice/{invoiceNumber}/print")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<String> printReceiptHtml(@PathVariable String invoiceNumber) {
        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(invoiceNumber);
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html><head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<style>");
        html.append("@media print { @page { margin: 0; size: 58mm auto; } body { margin: 0; padding: 5px; } }");
        html.append("body { font-family: 'Courier New', monospace; font-size: 12px; width: 58mm; margin: 0 auto; }");
        html.append(".header { text-align: center; margin-bottom: 10px; }");
        html.append(".line { border-bottom: 1px dashed #000; margin: 5px 0; }");
        html.append(".item { display: flex; justify-content: space-between; margin: 3px 0; }");
        html.append(".item-name { flex: 2; }");
        html.append(".item-qty { text-align: center; width: 40px; }");
        html.append(".item-price { text-align: right; width: 70px; }");
        html.append(".totals { margin-top: 10px; }");
        html.append(".total-row { display: flex; justify-content: space-between; margin: 2px 0; }");
        html.append(".footer { text-align: center; margin-top: 15px; font-size: 10px; }");
        html.append("</style></head><body>");
        
        html.append("<div class='header'>");
        html.append("<h3 style='margin: 0;'>BUSINESS MANAGEMENT SYSTEM</h3>");
        html.append("<p style='margin: 2px 0;'>RECEIPT</p>");
        html.append("</div>");
        
        html.append("<div class='line'></div>");
        html.append("<p style='margin: 2px 0;'><strong>Invoice:</strong> ").append(receipt.getInvoiceNumber()).append("</p>");
        html.append("<p style='margin: 2px 0;'><strong>Date:</strong> ").append(receipt.getSaleDate()).append("</p>");
        html.append("<p style='margin: 2px 0;'><strong>Cashier:</strong> ").append(receipt.getCashierName()).append("</p>");
        html.append("<p style='margin: 2px 0;'><strong>Customer:</strong> ").append(receipt.getCustomerName()).append("</p>");
        
        html.append("<div class='line'></div>");
        
        for (var item : receipt.getItems()) {
            html.append("<div class='item'>");
            html.append("<span class='item-name'>").append(item.getProductName()).append("</span>");
            html.append("<span class='item-qty'>x").append(item.getQuantity()).append("</span>");
            html.append("<span class='item-price'>").append(String.format("$%.2f", item.getSubtotal())).append("</span>");
            html.append("</div>");
            html.append("<div style='font-size: 10px; margin-left: 5px;'>@ $").append(String.format("%.2f", item.getUnitPrice())).append("</div>");
        }
        
        html.append("<div class='line'></div>");
        html.append("<div class='totals'>");
        html.append("<div class='total-row'><span>Subtotal:</span><span>$").append(String.format("%.2f", receipt.getSubtotal())).append("</span></div>");
        if (receipt.getTaxAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
            html.append("<div class='total-row'><span>Tax:</span><span>$").append(String.format("%.2f", receipt.getTaxAmount())).append("</span></div>");
        }
        if (receipt.getDiscountAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
            html.append("<div class='total-row'><span>Discount:</span><span>-$").append(String.format("%.2f", receipt.getDiscountAmount())).append("</span></div>");
        }
        html.append("<div class='total-row' style='font-weight: bold; font-size: 14px;'><span>TOTAL:</span><span>$").append(String.format("%.2f", receipt.getTotalAmount())).append("</span></div>");
        html.append("<div class='total-row'><span>Paid:</span><span>$").append(String.format("%.2f", receipt.getAmountPaid())).append("</span></div>");
        html.append("<div class='total-row'><span>Change:</span><span>$").append(String.format("%.2f", receipt.getChangeGiven())).append("</span></div>");
        html.append("</div>");
        
        html.append("<div class='line'></div>");
        html.append("<div class='footer'>");
        html.append("<p>Thank you for your business!</p>");
        html.append("<p>Please keep this receipt for your records.</p>");
        html.append("</div>");
        
        html.append("</body></html>");
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        headers.setContentDispositionFormData("inline", "receipt_" + receipt.getInvoiceNumber() + ".html");
        
        return ResponseEntity.ok()
                .headers(headers)
                .body(html.toString());
    }

    @GetMapping("/invoice/{invoiceNumber}/pdf")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<byte[]> generateReceiptPdf(@PathVariable String invoiceNumber) {
        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(invoiceNumber);
        
        // Generate PDF using OpenPDF library
        try {
            com.lowagie.text.Document document = new com.lowagie.text.Document(com.lowagie.text.PageSize.A4, 20, 20, 20, 20);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            com.lowagie.text.pdf.PdfWriter.getInstance(document, baos);
            
            document.open();
            
            // Header
            com.lowagie.text.Font titleFont = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 14, com.lowagie.text.Font.BOLD);
            com.lowagie.text.Font normalFont = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 10, com.lowagie.text.Font.NORMAL);
            com.lowagie.text.Font smallFont = new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 8, com.lowagie.text.Font.NORMAL);
            
            com.lowagie.text.Paragraph title = new com.lowagie.text.Paragraph("BUSINESS MANAGEMENT SYSTEM", titleFont);
            title.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            document.add(title);
            
            com.lowagie.text.Paragraph subtitle = new com.lowagie.text.Paragraph("RECEIPT", normalFont);
            subtitle.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            subtitle.setSpacingAfter(10);
            document.add(subtitle);
            
            document.add(new com.lowagie.text.Paragraph("Invoice: " + receipt.getInvoiceNumber(), normalFont));
            document.add(new com.lowagie.text.Paragraph("Date: " + receipt.getSaleDate(), normalFont));
            document.add(new com.lowagie.text.Paragraph("Cashier: " + receipt.getCashierName(), normalFont));
            document.add(new com.lowagie.text.Paragraph("Customer: " + receipt.getCustomerName(), normalFont));
            
            document.add(new com.lowagie.text.Paragraph("------------------------------------------------", smallFont));
            
            // Items table
            com.lowagie.text.pdf.PdfPTable table = new com.lowagie.text.pdf.PdfPTable(4);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{3f, 1f, 1f, 1f});
            
            table.addCell(new com.lowagie.text.Phrase("Item", smallFont));
            table.addCell(new com.lowagie.text.Phrase("Qty", smallFont));
            table.addCell(new com.lowagie.text.Phrase("Price", smallFont));
            table.addCell(new com.lowagie.text.Phrase("Total", smallFont));
            
            for (var item : receipt.getItems()) {
                table.addCell(new com.lowagie.text.Phrase(item.getProductName(), smallFont));
                table.addCell(new com.lowagie.text.Phrase(String.valueOf(item.getQuantity()), smallFont));
                table.addCell(new com.lowagie.text.Phrase(String.format("$%.2f", item.getUnitPrice()), smallFont));
                table.addCell(new com.lowagie.text.Phrase(String.format("$%.2f", item.getSubtotal()), smallFont));
            }
            
            document.add(table);
            
            document.add(new com.lowagie.text.Paragraph("------------------------------------------------", smallFont));
            
            // Totals
            document.add(new com.lowagie.text.Paragraph("Subtotal: $" + String.format("%.2f", receipt.getSubtotal()), normalFont));
            if (receipt.getTaxAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
                document.add(new com.lowagie.text.Paragraph("Tax: $" + String.format("%.2f", receipt.getTaxAmount()), normalFont));
            }
            if (receipt.getDiscountAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
                document.add(new com.lowagie.text.Paragraph("Discount: -$" + String.format("%.2f", receipt.getDiscountAmount()), normalFont));
            }
            document.add(new com.lowagie.text.Paragraph("TOTAL: $" + String.format("%.2f", receipt.getTotalAmount()), 
                new com.lowagie.text.Font(com.lowagie.text.Font.HELVETICA, 12, com.lowagie.text.Font.BOLD)));
            document.add(new com.lowagie.text.Paragraph("Paid: $" + String.format("%.2f", receipt.getAmountPaid()), normalFont));
            document.add(new com.lowagie.text.Paragraph("Change: $" + String.format("%.2f", receipt.getChangeGiven()), normalFont));
            
            document.add(new com.lowagie.text.Paragraph("------------------------------------------------", smallFont));
            
            // Footer
            com.lowagie.text.Paragraph footer = new com.lowagie.text.Paragraph("Thank you for your business!", smallFont);
            footer.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
            footer.setSpacingBefore(10);
            document.add(footer);
            
            document.close();
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "receipt_" + invoiceNumber + ".pdf");
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(baos.toByteArray());
                    
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF receipt", e);
        }
    }

    @GetMapping("/invoice/{invoiceNumber}/png")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
    public ResponseEntity<byte[]> generateReceiptPng(@PathVariable String invoiceNumber) {
        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(invoiceNumber);
        
        // Generate PNG using Java 2D graphics
        try {
            int width = 400;
            int lineHeight = 20;
            int padding = 20;
            int itemCount = receipt.getItems().size();
            int height = padding * 2 + lineHeight * (10 + itemCount);
            
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2d = image.createGraphics();
            
            // White background
            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect(0, 0, width, height);
            
            // Black text
            g2d.setColor(java.awt.Color.BLACK);
            g2d.setFont(new java.awt.Font("Courier New", java.awt.Font.PLAIN, 12));
            
            int y = padding + lineHeight;
            
            // Header
            g2d.drawString("BUSINESS MANAGEMENT SYSTEM", width / 2 - 100, y);
            y += lineHeight;
            g2d.drawString("RECEIPT", width / 2 - 30, y);
            y += lineHeight * 2;
            
            // Info
            g2d.drawString("Invoice: " + receipt.getInvoiceNumber(), padding, y);
            y += lineHeight;
            g2d.drawString("Date: " + receipt.getSaleDate(), padding, y);
            y += lineHeight;
            g2d.drawString("Cashier: " + receipt.getCashierName(), padding, y);
            y += lineHeight;
            g2d.drawString("Customer: " + receipt.getCustomerName(), padding, y);
            y += lineHeight * 2;
            
            // Items header
            g2d.drawString("Item", padding, y);
            g2d.drawString("Qty", width - 180, y);
            g2d.drawString("Price", width - 120, y);
            g2d.drawString("Total", width - 70, y);
            y += lineHeight;
            
            // Items
            for (var item : receipt.getItems()) {
                g2d.drawString(item.getProductName(), padding, y);
                g2d.drawString(String.valueOf(item.getQuantity()), width - 180, y);
                g2d.drawString(String.format("$%.2f", item.getUnitPrice()), width - 120, y);
                g2d.drawString(String.format("$%.2f", item.getSubtotal()), width - 70, y);
                y += lineHeight;
            }
            
            y += lineHeight;
            
            // Totals
            g2d.drawString("Subtotal: $" + String.format("%.2f", receipt.getSubtotal()), padding, y);
            y += lineHeight;
            if (receipt.getTaxAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
                g2d.drawString("Tax: $" + String.format("%.2f", receipt.getTaxAmount()), padding, y);
                y += lineHeight;
            }
            if (receipt.getDiscountAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
                g2d.drawString("Discount: -$" + String.format("%.2f", receipt.getDiscountAmount()), padding, y);
                y += lineHeight;
            }
            g2d.setFont(new java.awt.Font("Courier New", java.awt.Font.BOLD, 14));
            g2d.drawString("TOTAL: $" + String.format("%.2f", receipt.getTotalAmount()), padding, y);
            y += lineHeight;
            g2d.setFont(new java.awt.Font("Courier New", java.awt.Font.PLAIN, 12));
            g2d.drawString("Paid: $" + String.format("%.2f", receipt.getAmountPaid()), padding, y);
            y += lineHeight;
            g2d.drawString("Change: $" + String.format("%.2f", receipt.getChangeGiven()), padding, y);
            y += lineHeight * 2;
            
            // Footer
            g2d.drawString("Thank you for your business!", width / 2 - 80, y);
            
            g2d.dispose();
            
            // Convert to PNG bytes
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", baos);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentDispositionFormData("attachment", "receipt_" + invoiceNumber + ".png");
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(baos.toByteArray());
                    
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PNG receipt", e);
        }
    }

    private String buildReceiptHtml(ReceiptDto receipt) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html><head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<style>");
        html.append("@media print { @page { margin: 0; size: 58mm auto; } body { margin: 0; padding: 5px; } }");
        html.append("body { font-family: 'Courier New', monospace; font-size: 12px; width: 58mm; margin: 0 auto; }");
        html.append(".header { text-align: center; margin-bottom: 10px; }");
        html.append(".line { border-bottom: 1px dashed #000; margin: 5px 0; }");
        html.append(".item { display: flex; justify-content: space-between; margin: 3px 0; }");
        html.append(".item-name { flex: 2; }");
        html.append(".item-qty { text-align: center; width: 40px; }");
        html.append(".item-price { text-align: right; width: 70px; }");
        html.append(".totals { margin-top: 10px; }");
        html.append(".total-row { display: flex; justify-content: space-between; margin: 2px 0; }");
        html.append(".footer { text-align: center; margin-top: 15px; font-size: 10px; }");
        html.append("</style></head><body>");
        
        html.append("<div class='header'>");
        html.append("<h3 style='margin: 0;'>BUSINESS MANAGEMENT SYSTEM</h3>");
        html.append("<p style='margin: 2px 0;'>RECEIPT</p>");
        html.append("</div>");
        
        html.append("<div class='line'></div>");
        html.append("<p style='margin: 2px 0;'><strong>Invoice:</strong> ").append(receipt.getInvoiceNumber()).append("</p>");
        html.append("<p style='margin: 2px 0;'><strong>Date:</strong> ").append(receipt.getSaleDate()).append("</p>");
        html.append("<p style='margin: 2px 0;'><strong>Cashier:</strong> ").append(receipt.getCashierName()).append("</p>");
        html.append("<p style='margin: 2px 0;'><strong>Customer:</strong> ").append(receipt.getCustomerName()).append("</p>");
        
        html.append("<div class='line'></div>");
        
        for (var item : receipt.getItems()) {
            html.append("<div class='item'>");
            html.append("<span class='item-name'>").append(item.getProductName()).append("</span>");
            html.append("<span class='item-qty'>x").append(item.getQuantity()).append("</span>");
            html.append("<span class='item-price'>").append(String.format("$%.2f", item.getSubtotal())).append("</span>");
            html.append("</div>");
            html.append("<div style='font-size: 10px; margin-left: 5px;'>@ $").append(String.format("%.2f", item.getUnitPrice())).append("</div>");
        }
        
        html.append("<div class='line'></div>");
        html.append("<div class='totals'>");
        html.append("<div class='total-row'><span>Subtotal:</span><span>$").append(String.format("%.2f", receipt.getSubtotal())).append("</span></div>");
        if (receipt.getTaxAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
            html.append("<div class='total-row'><span>Tax:</span><span>$").append(String.format("%.2f", receipt.getTaxAmount())).append("</span></div>");
        }
        if (receipt.getDiscountAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
            html.append("<div class='total-row'><span>Discount:</span><span>-$").append(String.format("%.2f", receipt.getDiscountAmount())).append("</span></div>");
        }
        html.append("<div class='total-row' style='font-weight: bold; font-size: 14px;'><span>TOTAL:</span><span>$").append(String.format("%.2f", receipt.getTotalAmount())).append("</span></div>");
        html.append("<div class='total-row'><span>Paid:</span><span>$").append(String.format("%.2f", receipt.getAmountPaid())).append("</span></div>");
        html.append("<div class='total-row'><span>Change:</span><span>$").append(String.format("%.2f", receipt.getChangeGiven())).append("</span></div>");
        html.append("</div>");
        
        html.append("<div class='line'></div>");
        html.append("<div class='footer'>");
        html.append("<p>Thank you for your business!</p>");
        html.append("<p>Please keep this receipt for your records.</p>");
        html.append("</div>");
        
        html.append("</body></html>");
        
        return html.toString();
    }
}
