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

        // Header: Shop name, address, phone (respect show toggles + header alignment)
        boolean showShopName = customization.getShowShopName() == null || customization.getShowShopName();
        boolean showAddress  = customization.getShowAddress()  == null || customization.getShowAddress();
        boolean showPhone    = customization.getShowPhone()    == null || customization.getShowPhone();

        if (showShopName) {
            addAlignedLine(shopInfo != null ? shopInfo.getShopName() : "Shop");
        }
        if (showAddress && shopInfo != null && shopInfo.getAddress() != null && !shopInfo.getAddress().isEmpty()) {
            addAlignedLine(shopInfo.getAddress());
        }
        if (showPhone && shopInfo != null && shopInfo.getPhone() != null && !shopInfo.getPhone().isEmpty()) {
            addAlignedLine(shopInfo.getPhone());
        }

        addLine(""); // Blank line
        addDivider();

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

        // Main message (mirrors the on-screen receipt: between meta and items)
        if (customization.getMainMessage() != null && !customization.getMainMessage().isBlank()) {
            addLine("");
            addCenteredLine(customization.getMainMessage());
        }

        addLine("");
        addDivider();

        // Items use a flexible two-line layout: name and total, then quantity x unit price.
        for (var item : receipt.getItems()) {
            String name = item.getProductName() != null ? item.getProductName() : "";
            String total = formatCurrency(item.getSubtotal());
            int nameW = Math.max(1, lineWidth - total.length() - 1);
            List<String> nameLines = wrapText(name, nameW);
            addLine(padRight(nameLines.get(0), nameW) + " " + total);
            for (int lineIndex = 1; lineIndex < nameLines.size(); lineIndex++) {
                addLine(nameLines.get(lineIndex));
            }
                String unit = item.getUnit() == null ? "" : item.getUnit().trim();
                addLine("  " + item.getQuantity() + (unit.isEmpty() ? "" : " " + unit)
                    + " x " + formatPlain(item.getUnitPrice()));
        }

        addLine("");
        addDivider();

        // Totals section (respect showTax / showDiscount toggles)
        boolean showTaxLine      = customization.getShowTax() == null || customization.getShowTax();
        boolean showDiscountLine = customization.getShowDiscount() == null || customization.getShowDiscount();

        // Subtotal only when it differs from the total (mirrors the on-screen receipt)
        BigDecimal subtotal = receipt.getSubtotal() == null ? BigDecimal.ZERO : receipt.getSubtotal();
        BigDecimal totalAmount = receipt.getTotalAmount() == null ? BigDecimal.ZERO : receipt.getTotalAmount();
        if (subtotal.compareTo(BigDecimal.ZERO) > 0 && subtotal.compareTo(totalAmount) != 0) {
            addTotalLine("Subtotal", subtotal);
        }

        if (showTaxLine && receipt.getTaxAmount().compareTo(BigDecimal.ZERO) > 0) {
            addTotalLine("Tax", receipt.getTaxAmount());
        }

        if (showDiscountLine && receipt.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            addTotalLine("Discount", receipt.getDiscountAmount().negate());
        }

        // Main total (bold for visual emphasis in other formats)
        addLine("");
        addTotalLineStrong("TOTAL", receipt.getTotalAmount());
        addTotalLine("Paid", receipt.getAmountPaid());
        if (receipt.getChangeGiven() != null && receipt.getChangeGiven().compareTo(BigDecimal.ZERO) > 0) {
            addTotalLine("Change", receipt.getChangeGiven());
        }

        // Credit info (credit sales only, respects the show/hide toggle)
        boolean showCreditInfo = customization.getShowCreditInfo() == null || customization.getShowCreditInfo();
        if (showCreditInfo && "CREDIT".equalsIgnoreCase(receipt.getSaleType())) {
            addLine("");
            addCenteredLine("*** CREDIT SALE ***");
            addTotalLine("Balance Due", receipt.getBalanceDue());
            if (receipt.getDueDate() != null) {
                addLine("Due Date: " + receipt.getDueDate());
            }
        }

        addLine("");
        addDivider();

        // Footer customization text
        addCenteredLine(customization.getFooterText());
        
        addLine("");
        addLine("");

        return lines;
    }

    /**
     * Get normalized lines for plain-text output (console, ESC/POS).
     */
    public List<String> getLines() {
        return new ArrayList<>(lines);
    }

    /**
     * Divider character chosen from the customization's divider style:
     * dashed → '-', solid → '=', dotted → '.', none → no line at all.
     */
    private String dividerChar() {
        String style = customization.getDividerStyle() == null || customization.getDividerStyle().isBlank()
                ? "dashed" : customization.getDividerStyle();
        return switch (style) {
            case "solid" -> "=";
            case "dotted" -> ".";
            case "none" -> null;
            default -> "-";
        };
    }

    /** Adds a full-width divider line honouring the divider style (blank line for 'none'). */
    private void addDivider() {
        String ch = dividerChar();
        if (ch == null) {
            addLine("");
            return;
        }
        addLine(repeatChar(ch, lineWidth));
    }

    /** Adds a header line respecting the customization's header alignment. */
    private void addAlignedLine(String text) {
        if (text == null || text.isEmpty()) {
            addLine("");
            return;
        }
        String align = customization.getHeaderAlign() == null ? "center" : customization.getHeaderAlign();
        switch (align) {
            case "left" -> addLine(text);
            case "right" -> addLine(padLeft(text, lineWidth));
            default -> addCenteredLine(text);
        }
    }

    /**
     * Whether the shop logo should be rendered (showLogo toggle, default true).
     */
    public boolean isShowLogo() {
        return customization.getShowLogo() == null || customization.getShowLogo();
    }

    /**
     * Configured logo size in px, clamped to a sane print range.
     */
    public int getLogoSize() {
        Integer size = customization.getLogoSize();
        if (size == null) return 80;
        return Math.max(20, Math.min(160, size));
    }

    /**
     * Header alignment: left / center / right (default center).
     */
    public String getHeaderAlign() {
        String align = customization.getHeaderAlign();
        if (align == null) return "center";
        return switch (align.toLowerCase().trim()) {
            case "left" -> "left";
            case "right" -> "right";
            default -> "center";
        };
    }

    /**
     * Configured font size key: small / normal / large.
     */
    public String getFontSize() {
        String size = customization.getFontSize();
        if (size == null) return "normal";
        return switch (size.toLowerCase().trim()) {
            case "small" -> "small";
            case "large" -> "large";
            default -> "normal";
        };
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

    private List<String> wrapText(String text, int width) {
        List<String> result = new ArrayList<>();
        String remaining = text == null ? "" : text.trim();
        if (remaining.isEmpty()) {
            result.add("");
            return result;
        }
        while (remaining.length() > width) {
            int breakAt = remaining.lastIndexOf(' ', width);
            if (breakAt <= 0) breakAt = width;
            result.add(remaining.substring(0, breakAt).trim());
            remaining = remaining.substring(breakAt).trim();
        }
        result.add(remaining);
        return result;
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
        return df.format(amount) + " " + symbol;
    }

    /**
     * Numeric amount without the currency unit — used in the item columns so
     * they match the on-screen/direct-print receipt.
     */
    public String formatPlain(BigDecimal amount) {
        if (amount == null) amount = BigDecimal.ZERO;
        return new DecimalFormat("#,##0.00").format(amount);
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
