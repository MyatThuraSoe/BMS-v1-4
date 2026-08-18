package com.bms.util;

import com.bms.dto.receipt.ReceiptDto;
import com.bms.dto.response.ShopInfoResponse;
import com.bms.entity.ReceiptCustomization;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified receipt layout builder used by HTML, PDF, and PNG generators.
 * Ensures consistent formatting across all receipt formats.
 */
public class ReceiptLayoutBuilder {
    public static final int DEFAULT_PAPER_WIDTH_MM = 58;
    private static final double CHARS_PER_MM = 1.0 / 1.47;
    private static final String HORIZONTAL_LINE = "-";

    private final ReceiptDto receipt;
    private final ShopInfoResponse shopInfo;
    private final ReceiptCustomization customization;
    private final String currency;
    private final int paperWidthMm;
    private final int lineWidth;
    private final List<String> lines;

    public ReceiptLayoutBuilder(ReceiptDto receipt, ShopInfoResponse shopInfo, ReceiptCustomization customization) {
        this.receipt = receipt;
        this.shopInfo = shopInfo;
        this.customization = customization;
        this.currency = shopInfo != null ? shopInfo.getCurrency() : "USD";
        this.paperWidthMm = parsePaperWidth(customization.getPaperSize() != null ? customization.getPaperSize() : "58");
        this.lineWidth = Math.max(16, (int) Math.round(this.paperWidthMm * CHARS_PER_MM));
        this.lines = new ArrayList<>();
    }

    /**
     * Build the complete receipt layout in normalized form (list of lines).
     * This is the single source of truth for receipt structure.
     */
    public List<String> build() {
        lines.clear();

        // Header: Shop name, address, phone (respect show toggles)
        boolean showShopName = customization.getShowShopName() == null || customization.getShowShopName();
        boolean showAddress  = customization.getShowAddress()  == null || customization.getShowAddress();
        boolean showPhone    = customization.getShowPhone()    == null || customization.getShowPhone();

        if (showShopName) {
            addCenteredLine(shopInfo != null ? shopInfo.getShopName() : "Shop");
        }
        if (showAddress && shopInfo != null && shopInfo.getAddress() != null && !shopInfo.getAddress().isEmpty()) {
            addCenteredLine(shopInfo.getAddress());
        }
        if (showPhone && shopInfo != null && shopInfo.getPhone() != null && !shopInfo.getPhone().isEmpty()) {
            addCenteredLine(shopInfo.getPhone());
        }

        addLine(""); // Blank line
        addLine(repeatChar(HORIZONTAL_LINE, lineWidth));

        // Header customization text (no "RECEIPT" label)
        if (customization.getHeaderText() != null && !customization.getHeaderText().isBlank()) {
            addCenteredLine(customization.getHeaderText());
        }

        // Invoice details
        addLine("");
        addLine("Invoice No: " + receipt.getInvoiceNumber());
        addLine("Date: " + formatDateTime(receipt.getSaleDate()));
        
        // Customer (only if not a walk-in)
        if (receipt.getCustomerName() != null && !receipt.getCustomerName().isBlank() && !"Walk-in".equalsIgnoreCase(receipt.getCustomerName())) {
            addLine("Customer: " + receipt.getCustomerName());
        }

        addLine("");
        addLine(repeatChar(HORIZONTAL_LINE, lineWidth));

        // Items (4-column: Item, Qty, Price, Amount)
        int qtyW = 4;
        int priceW = Math.max(7, formatCurrency(BigDecimal.valueOf(9999999.99)).length());
        int amountW = Math.max(9, formatCurrency(BigDecimal.valueOf(9999999.99)).length());
        int gap = 1;
        int nameW = Math.max(6, lineWidth - qtyW - priceW - amountW - (gap * 3));

        addLine(fourColumnRow("Item", "Qty", "Price", "Amount", nameW, qtyW, priceW, amountW, gap));
        for (var item : receipt.getItems()) {
            String name = item.getProductName() != null ? item.getProductName() : "";
            addLine(fourColumnRow(
                    name,
                    String.valueOf(item.getQuantity()),
                    formatCurrency(item.getUnitPrice()),
                    formatCurrency(item.getSubtotal()),
                    nameW, qtyW, priceW, amountW, gap));
        }

        addLine("");
        addLine(repeatChar(HORIZONTAL_LINE, lineWidth));

        // Totals section
        addTotalLine("Subtotal", receipt.getSubtotal());
        
        if (receipt.getTaxAmount().compareTo(BigDecimal.ZERO) > 0) {
            addTotalLine("Tax", receipt.getTaxAmount());
        }
        
        if (receipt.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            addTotalLine("Discount", receipt.getDiscountAmount().negate());
        }

        // Main total (bold for visual emphasis in other formats)
        addLine("");
        addTotalLineStrong("TOTAL", receipt.getTotalAmount());
        addTotalLine("Paid", receipt.getAmountPaid());
        addTotalLine("Change", receipt.getChangeGiven());

        addLine("");
        addLine(repeatChar(HORIZONTAL_LINE, lineWidth));

        // Footer customization text
        addCenteredLine(customization.getFooterText());
        
        addLine("");
        addLine("");

        return lines;
    }

    /**
     * Get normalized lines for plain-text output (console, QZ Tray ESC/POS).
     */
    public List<String> getLines() {
        return new ArrayList<>(lines);
    }

    /**
     * Get the line width in characters for text-based formats.
     */
    public int getLineWidth() {
        return lineWidth;
    }

    /**
     * Get paper width in millimeters.
     */
    public int getPaperWidthMm() {
        return paperWidthMm;
    }

    /**
     * Get paper width in pixels (for HTML/PDF preview).
     */
    public int getPaperWidthPixels() {
        return (int) Math.round(paperWidthMm * (400.0 / 58.0));
    }

    private void addLine(String text) {
        lines.add(text != null ? text : "");
    }

    private void addCenteredLine(String text) {
        if (text == null || text.isEmpty()) {
            addLine("");
            return;
        }
        int padding = Math.max(0, (lineWidth - text.length()) / 2);
        addLine(repeatChar(" ", padding) + text);
    }

    private void addTotalLine(String label, BigDecimal amount) {
        String price = formatCurrency(amount);
        String line = label + ":";
        int padding = Math.max(1, lineWidth - line.length() - price.length());
        addLine(line + repeatChar(" ", padding) + price);
    }

    private void addTotalLineStrong(String label, BigDecimal amount) {
        // For plain text output, same as normal; formatters will handle bold styling
        addTotalLine(label, amount);
    }

    private String repeatChar(String ch, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    private String fourColumnRow(String name, String qty, String price, String amount,
                                 int nameW, int qtyW, int priceW, int amountW, int gap) {
        String pad = repeatChar(" ", gap);
        return padRight(truncate(name, nameW), nameW) + pad
                + padLeft(qty, qtyW) + pad
                + padLeft(price, priceW) + pad
                + padLeft(amount, amountW);
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        if (max <= 1) return text.substring(0, max);
        return text.substring(0, max - 1) + ".";
    }

    private String padLeft(String text, int width) {
        if (text == null) text = "";
        if (text.length() >= width) return text;
        return repeatChar(" ", width - text.length()) + text;
    }

    private String padRight(String text, int width) {
        if (text == null) text = "";
        if (text.length() >= width) return text;
        return text + repeatChar(" ", width - text.length());
    }

    private String formatDateTime(LocalDateTime saleDate) {
        if (saleDate == null) return "";
        String datePart = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(saleDate);
        if ("24".equalsIgnoreCase(customization.getTimeFormat())) {
            String timePart = DateTimeFormatter.ofPattern("HH:mm").format(saleDate);
            return datePart + " " + timePart;
        }
        int hour = saleDate.getHour();
        int hour12 = hour % 12 == 0 ? 12 : hour % 12;
        String ampm = hour < 12 ? "am" : "pm";
        return datePart + " " + hour12 + ":" + String.format("%02d", saleDate.getMinute()) + ampm;
    }

    public String formatCurrency(BigDecimal amount) {
        if (amount == null) amount = BigDecimal.ZERO;
        DecimalFormat df = new DecimalFormat("#,##0.00");

        if ("MMK".equalsIgnoreCase(currency)) {
            return df.format(amount) + " Ks";
        }

        String symbol;
        switch (currency) {
            case "THB": symbol = "฿"; break;
            case "EUR": symbol = "€"; break;
            case "GBP": symbol = "£"; break;
            case "SGD": symbol = "S$"; break;
            case "INR": symbol = "₹"; break;
            default: symbol = "$"; break;
        }
        return symbol + df.format(amount);
    }

    private static int parsePaperWidth(String paperSize) {
        if (paperSize == null || paperSize.isBlank()) return DEFAULT_PAPER_WIDTH_MM;
        String digits = paperSize.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return DEFAULT_PAPER_WIDTH_MM;
        try {
            int mm = Integer.parseInt(digits);
            if (mm >= 20 && mm <= 200) return mm;
        } catch (NumberFormatException ignored) {
        }
        return DEFAULT_PAPER_WIDTH_MM;
    }

    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
