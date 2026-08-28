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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
    private final ConcurrentLinkedQueue<PrintJob> pendingJobs = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, PrintJobStatus> jobStatuses = new ConcurrentHashMap<>();

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

    /** Queue a receipt for the Electron client running on this server. */
    public Map<String, String> enqueueReceipt(String invoiceNumber) {
        String jobId = UUID.randomUUID().toString();
        pendingJobs.add(new PrintJob(jobId, invoiceNumber, getConfiguredPrinterName()));
        jobStatuses.put(jobId, PrintJobStatus.QUEUED);
        return Map.of("jobId", jobId, "invoiceNumber", invoiceNumber, "status", PrintJobStatus.QUEUED.name());
    }

    /** Claim the next queued receipt; only the Electron client should call this. */
    public Map<String, String> claimNextReceipt() {
        PrintJob job = pendingJobs.poll();
        if (job == null) {
            return Map.of("status", "EMPTY");
        }
        jobStatuses.put(job.id(), PrintJobStatus.PRINTING);
        return Map.of("jobId", job.id(), "invoiceNumber", job.invoiceNumber(),
            "printerName", job.printerName(), "status", PrintJobStatus.PRINTING.name());
    }

    /** Record the Electron client's final print result. */
    public void completeReceipt(String jobId, boolean success) {
        if (jobId != null && jobStatuses.containsKey(jobId)) {
            jobStatuses.put(jobId, success ? PrintJobStatus.COMPLETED : PrintJobStatus.FAILED);
        }
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
        spool(preferredPrinter, lines, 80, null, 0, "center", "normal");
    }

    /** Renders the invoice as fixed-width receipt text and prints it. */
    @Transactional(readOnly = true)
    public void printInvoice(String invoiceNumber, String preferredPrinter) {
        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(invoiceNumber);
        var shopInfo = shopInfoService.getShopInfo();
        ReceiptCustomization customization = receiptCustomizationService.getCustomization();

        com.bms.util.ReceiptLayoutBuilder builder =
                new com.bms.util.ReceiptLayoutBuilder(receipt, shopInfo, customization);
        List<String> lines = builder.build();

        // Load the shop logo so it prints above the text (respects showLogo/logoSize/headerAlign)
        java.awt.image.BufferedImage logo = null;
        if (builder.isShowLogo()) {
            var payload = shopInfoService.getLogoBytesOrNull();
            if (payload != null && payload.data() != null) {
                try {
                    logo = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(payload.data()));
                } catch (Exception ignored) {
                    logo = null; // unreadable image → print text-only
                }
            }
        }
        spool(preferredPrinter, lines, builder.getPaperWidthMm(), logo, builder.getLogoSize(),
            builder.getHeaderAlign(), builder.getFontSize());
    }

    private enum PrintJobStatus { QUEUED, PRINTING, COMPLETED, FAILED }

    private record PrintJob(String id, String invoiceNumber, String printerName) {}

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
     * any Windows printer driver can rasterize it (no dialogs). The shop logo,
     * when provided, is drawn above the text on the first page.
     */
    private void spool(String preferredPrinter, List<String> lines, double paperWidthMm,
                       java.awt.image.BufferedImage logo, int logoSizePx, String headerAlign,
                       String fontSizeKey) {
        String target = (preferredPrinter != null && !preferredPrinter.isBlank())
                ? preferredPrinter : getConfiguredPrinterName();
        javax.print.PrintService service = resolvePrinter(target);

        try {
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setPrintService(service);
            job.setJobName("LumiPOS Receipt");
                PageFormat pageFormat = createReceiptPageFormat(lines, paperWidthMm, logo, logoSizePx, fontSizeKey);
                job.setPrintable(new MonospacedPrintable(lines, paperWidthMm, logo, logoSizePx, headerAlign,
                    fontSizeKey), pageFormat);
            job.print();
        } catch (PrinterException e) {
            throw new BusinessException("Counter print failed on '" + service.getName() + "': "
                    + e.getMessage());
        }
    }

    private PageFormat createReceiptPageFormat(List<String> lines, double paperWidthMm,
                                               java.awt.image.BufferedImage logo, int logoSizePx,
                                               String fontSizeKey) {
        double widthPt = mmToPt(Math.max(40, paperWidthMm));
        double lineHeightPt = configuredFontSize(fontSizeKey) * 1.2;
        double logoHeightPt = logo == null ? 0 : Math.min(widthPt * 0.45, logoSizePx * 0.75) + lineHeightPt * 0.5;
        double heightPt = Math.max(mmToPt(80), lines.size() * lineHeightPt + logoHeightPt + lineHeightPt);

        Paper paper = new Paper();
        paper.setSize(widthPt, heightPt);
        paper.setImageableArea(0, 0, widthPt, heightPt);

        PageFormat pageFormat = new PageFormat();
        pageFormat.setPaper(paper);
        return pageFormat;
    }

    private static double mmToPt(double mm) {
        return mm * 72.0 / 25.4;
    }

    private static float configuredFontSize(String fontSizeKey) {
        return switch (fontSizeKey == null ? "normal" : fontSizeKey) {
            // Match the frontend's CSS 10px / 13px / 15px at 96 DPI.
            case "small" -> 7.5f;
            case "large" -> 11.25f;
            default -> 9.75f;
        };
    }

    /**
     * Draws receipt lines in Courier-like monospace, auto-scaled to the paper
     * width, paginating when the content exceeds one page height. An optional
     * shop logo is drawn centred (or aligned) above the text on page 1.
     */
    static class MonospacedPrintable implements Printable {
        private final List<String> lines;
        private final double paperWidthMm;
        private final java.awt.image.BufferedImage logo;
        private final int logoSizePx;
        private final String headerAlign;
        private final String fontSizeKey;

        MonospacedPrintable(List<String> lines, double paperWidthMm,
                    java.awt.image.BufferedImage logo, int logoSizePx, String headerAlign,
                    String fontSizeKey) {
            this.lines = lines;
            this.paperWidthMm = Math.max(40, paperWidthMm);
            this.logo = logo;
            this.logoSizePx = logoSizePx;
            this.headerAlign = headerAlign == null ? "center" : headerAlign;
            this.fontSizeKey = fontSizeKey == null ? "normal" : fontSizeKey;
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
            float configuredSize = configuredFontSize(fontSizeKey);
            float fontSize = Math.min(configuredSize, (float) Math.max(6, widthPt / (maxCols * 0.62)));
            float lineHeight = fontSize * 1.2f;

            g2.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
            Map<TextAttribute, Object> attrs = new java.util.HashMap<>();
            attrs.put(TextAttribute.FONT, new Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(fontSize));
            g2.setFont(new Font(attrs));
            g2.setPaint(java.awt.Color.BLACK);

            float y = lineHeight;

            // Draw the shop logo above the text on the first page only
            double logoBlockHeight = 0;
            if (logo != null && pageIndex == 0) {
                double targetHpt = Math.min(mmToPt(paperWidthMm) * 0.45, logoSizePx * 0.75);
                double aspect = (double) logo.getWidth() / (double) logo.getHeight();
                double drawW = targetHpt * aspect;
                double drawH = targetHpt;
                if (drawW > widthPt) {
                    drawW = widthPt;
                    drawH = drawW / aspect;
                }
                double x;
                switch (headerAlign) {
                    case "left" -> x = 0;
                    case "right" -> x = widthPt - drawW;
                    default -> x = (widthPt - drawW) / 2;
                }
                g2.drawImage(logo, (int) Math.round(x), 0,
                        (int) Math.round(drawW), (int) Math.round(drawH), null);
                logoBlockHeight = drawH + lineHeight * 0.5;
                y += (float) logoBlockHeight;
            }

            int totalLines = lines.size();
            int linesPerPage = (int) Math.max(1, (heightPt - logoBlockHeight) / lineHeight);
            int firstLine = pageIndex * linesPerPage;
            if (firstLine >= totalLines) {
                return NO_SUCH_PAGE;
            }

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
