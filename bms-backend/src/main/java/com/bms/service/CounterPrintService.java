package com.bms.service;

import com.bms.dto.receipt.ReceiptDto;
import com.bms.entity.ReceiptCustomization;
import com.bms.entity.SystemSetting;
import com.bms.exception.BusinessException;
import com.bms.repository.SystemSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prints receipts on printers attached to the SERVER computer.
 *
 * This is what lets browser users (phones/tablets/other PCs) ring the shop's
 * counter printer without installing anything locally: they call one endpoint,
 * and the backend renders fixed-width receipt text onto whichever printer is
 * configured (or the Windows default) using an AWT {@link Printable} — silent,
 * dialog-free, and driver-independent.
 *
 * The chosen printer name is stored in system_settings under
 * {@link #CONFIG_KEY}; blank means "use the server's default printer".
 */
@Service
public class CounterPrintService {

    public static final String CONFIG_KEY = "receipt.counter.printer";

    private final ReceiptService receiptService;
    private final ShopInfoService shopInfoService;
    private final ReceiptCustomizationService receiptCustomizationService;
    private final SystemSettingRepository systemSettingRepository;

    public CounterPrintService(ReceiptService receiptService,
                               ShopInfoService shopInfoService,
                               ReceiptCustomizationService receiptCustomizationService,
                               SystemSettingRepository systemSettingRepository) {
        this.receiptService = receiptService;
        this.shopInfoService = shopInfoService;
        this.receiptCustomizationService = receiptCustomizationService;
        this.systemSettingRepository = systemSettingRepository;
    }

    /** All printers installed on the server computer, default first. */
    public Map<String, Object> listPrinters() {
        List<String> names = new ArrayList<>();
        for (javax.print.PrintService service : javax.print.PrintServiceLookup.lookupPrintServices(null, null)) {
            names.add(service.getName());
        }
        String def = getDefaultPrinterName();
        if (def != null) {
            names.remove(def);
            names.add(0, def);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("printers", names);
        result.put("default", def);
        result.put("configured", getConfiguredPrinterName());
        return result;
    }

    @Transactional
    public void saveConfiguredPrinter(String printerName) {
        SystemSetting setting = systemSettingRepository.findBySettingKey(CONFIG_KEY)
                .orElseGet(() -> {
                    SystemSetting s = new SystemSetting();
                    s.setSettingKey(CONFIG_KEY);
                    s.setDescription("Counter receipt printer installed on the LumiPOS server computer");
                    return s;
                });
        setting.setSettingValue(printerName == null ? "" : printerName.trim());
        systemSettingRepository.save(setting);
    }

    @Transactional(readOnly = true)
    public String getConfiguredPrinterName() {
        return systemSettingRepository.findBySettingKey(CONFIG_KEY)
                .map(SystemSetting::getSettingValue)
                .map(String::trim)
                .orElse("");
    }

    /** Prints a short test page so admins can verify wiring in seconds. */
    public void printTestPage(String preferredPrinter) {
        List<String> lines = new ArrayList<>();
        lines.add("********************************");
        lines.add("   LumiPOS COUNTER PRINT TEST");
        lines.add("********************************");
        lines.add("");
        lines.add("If you can read this, the counter");
        lines.add("printer is configured correctly.");
        lines.add("");
        lines.add(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        lines.add("");
        spool(preferredPrinter, lines, 80);
    }

    /** Renders the invoice as fixed-width receipt text and prints it. */
    @Transactional(readOnly = true)
    public void printInvoice(String invoiceNumber, String preferredPrinter) {
        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(invoiceNumber);
        var shopInfo = shopInfoService.getShopInfo();
        ReceiptCustomization customization = receiptCustomizationService.getCustomization();

        com.bms.util.ReceiptLayoutBuilder builder =
                new com.bms.util.ReceiptLayoutBuilder(receipt, shopInfo, customization);
        List<String> lines = new ArrayList<>(builder.build());
        lines.add("");
        lines.add("");
        spool(preferredPrinter, lines, builder.getPaperWidthMm());
    }

    private String getDefaultPrinterName() {
        javax.print.PrintService def = javax.print.PrintServiceLookup.lookupDefaultPrintService();
        return def != null ? def.getName() : null;
    }

    private javax.print.PrintService resolvePrinter(String name) {
        javax.print.PrintService defaultService = javax.print.PrintServiceLookup.lookupDefaultPrintService();
        if (name != null && !name.isBlank()) {
            for (javax.print.PrintService service : javax.print.PrintServiceLookup.lookupPrintServices(null, null)) {
                if (service.getName().equals(name)) {
                    return service;
                }
            }
            throw new BusinessException(
                    "Printer '" + name + "' is not installed on the LumiPOS server computer");
        }
        if (defaultService != null) {
            return defaultService;
        }
        throw new BusinessException(
                "No default printer on the LumiPOS server computer. Install one or pick a counter printer in Settings.");
    }

    /**
     * Silent print: renders monospaced receipt lines via an AWT Printable so
     * any Windows printer driver can rasterize it (no dialogs).
     */
    private void spool(String preferredPrinter, List<String> lines, double paperWidthMm) {
        String target = (preferredPrinter != null && !preferredPrinter.isBlank())
                ? preferredPrinter : getConfiguredPrinterName();
        javax.print.PrintService service = resolvePrinter(target);

        try {
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(service);
            job.setJobName("LumiPOS Receipt");
            job.setPrintable(new MonospacedPrintable(lines, paperWidthMm));
            job.print();
        } catch (PrinterException e) {
            throw new BusinessException("Counter print failed on '" + service.getName() + "': "
                    + e.getMessage());
        }
    }

    /**
     * Draws receipt lines in Courier-like monospace, auto-scaled to the paper
     * width, paginating when the content exceeds one page height.
     */
    static class MonospacedPrintable implements Printable {
        private final List<String> lines;
        private final double paperWidthMm;

        MonospacedPrintable(List<String> lines, double paperWidthMm) {
            this.lines = lines;
            this.paperWidthMm = Math.max(40, paperWidthMm);
        }

        @Override
        public int print(Graphics graphics, PageFormat pageFormat, int pageIndex) {
            if (!(graphics instanceof Graphics2D g2)) {
                return NO_SUCH_PAGE;
            }
            double widthPt = pageFormat.getImageableWidth();
            double heightPt = pageFormat.getImageableHeight();
            if (widthPt <= 0 || heightPt <= 0) {
                // Some drivers report no imageable area until configured — assume roll width
                widthPt = mmToPt(paperWidthMm);
                heightPt = 11 * 72; // generous virtual page; we paginate manually below
            }

            int maxCols = 1;
            for (String line : lines) {
                maxCols = Math.max(maxCols, line.length());
            }
            // Monospace advance ≈ 0.6 × font size
            float fontSize = (float) Math.max(6, Math.min(14, widthPt / (maxCols * 0.62)));
            float lineHeight = fontSize * 1.25f;

            g2.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
            Map<TextAttribute, Object> attrs = new java.util.HashMap<>();
            attrs.put(TextAttribute.FONT, new Font(Font.MONOSPACED, Font.PLAIN, Math.round(fontSize)));
            g2.setFont(new Font(attrs));
            g2.setPaint(java.awt.Color.BLACK);

            int totalLines = lines.size();
            int linesPerPage = (int) Math.max(1, heightPt / lineHeight);
            int firstLine = pageIndex * linesPerPage;
            if (firstLine >= totalLines) {
                return NO_SUCH_PAGE;
            }

            float y = lineHeight;
            for (int i = firstLine; i < totalLines && i < firstLine + linesPerPage; i++) {
                g2.drawString(lines.get(i), 0, y);
                y += lineHeight;
            }
            return PAGE_EXISTS;
        }

        private static double mmToPt(double mm) {
            return mm * 72.0 / 25.4;
        }
    }
}
