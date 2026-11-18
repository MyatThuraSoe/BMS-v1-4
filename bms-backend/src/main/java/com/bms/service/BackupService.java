package com.bms.service;

import com.bms.entity.*;
import com.bms.exception.StateConflictException;
import com.bms.repository.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BackupService {

    @Autowired
    private ShopTimeService shopTimeService;

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter JSON_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /**
     * How many backups of each kind a shop keeps in Google Drive. Thirty nights of
     * history is enough to recover from a mistake noticed late without the folder
     * growing until the Google account is full, which is how a shop loses its
     * safety net silently. Anything under three is not a history worth having.
     */
    public static final int DEFAULT_RETENTION_COUNT = 30;
    public static final int MINIMUM_RETENTION_COUNT = 3;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final PurchaseRepository purchaseRepository;
    private final PurchaseItemRepository purchaseItemRepository;
    private final ExpenseRepository expenseRepository;
    private final ArPaymentRepository arPaymentRepository;
    private final ReceiptCustomizationRepository receiptCustomizationRepository;
    private final OrderRepository orderRepository;

    private final BackupSettingRepository backupSettingRepository;
    private final GoogleDriveService googleDriveService;
    private final DataExportService dataExportService;
    private final SystemSettingRepository systemSettingRepository;
    private final DatabaseSnapshotService snapshotService;

    public BackupService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            CustomerRepository customerRepository,
            SupplierRepository supplierRepository,
            SaleRepository saleRepository,
            SaleItemRepository saleItemRepository,
            PurchaseRepository purchaseRepository,
            PurchaseItemRepository purchaseItemRepository,
            ExpenseRepository expenseRepository,
            ArPaymentRepository arPaymentRepository,
            ReceiptCustomizationRepository receiptCustomizationRepository,
            OrderRepository orderRepository,
            BackupSettingRepository backupSettingRepository,
            GoogleDriveService googleDriveService,
            DataExportService dataExportService,
            SystemSettingRepository systemSettingRepository,
            DatabaseSnapshotService snapshotService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.saleRepository = saleRepository;
        this.saleItemRepository = saleItemRepository;
        this.purchaseRepository = purchaseRepository;
        this.purchaseItemRepository = purchaseItemRepository;
        this.expenseRepository = expenseRepository;
        this.arPaymentRepository = arPaymentRepository;
        this.receiptCustomizationRepository = receiptCustomizationRepository;
        this.orderRepository = orderRepository;
        this.backupSettingRepository = backupSettingRepository;
        this.googleDriveService = googleDriveService;
        this.dataExportService = dataExportService;
        this.systemSettingRepository = systemSettingRepository;
        this.snapshotService = snapshotService;
    }

    @Transactional(readOnly = true)
    public void exportBackup(OutputStream outputStream, LocalDate startDate, LocalDate endDate) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            // Master data is always fully exported
            writeProductsSheet(workbook);
            writeCategoriesSheet(workbook);
            writeCustomersSheet(workbook);
            writeSuppliersSheet(workbook);

            // Fetch all transactional data first
            List<Sale> salesToExport = saleRepository.findAll();
            List<Purchase> purchasesToExport = purchaseRepository.findAll();
            List<Expense> expensesToExport = expenseRepository.findAll();

            // Filter transactional data if date range is provided
            if (startDate != null && endDate != null) {
                salesToExport = salesToExport.stream()
                        .filter(s -> isDateInRange(s.getSaleDate(), startDate, endDate))
                        .collect(Collectors.toList());

                purchasesToExport = purchasesToExport.stream()
                        .filter(p -> isDateInRange(p.getPurchaseDate(), startDate, endDate))
                        .collect(Collectors.toList());

                expensesToExport = expensesToExport.stream()
                        .filter(e -> isDateInRange(e.getExpenseDate(), startDate, endDate))
                        .collect(Collectors.toList());
            }

            writeSalesSheet(workbook, salesToExport);
            writeSaleItemsSheet(workbook, salesToExport); // Pass filtered sales to filter items
            writePurchasesSheet(workbook, purchasesToExport);
            writePurchaseItemsSheet(workbook, purchasesToExport); // Pass filtered purchases to filter items
            writeExpensesSheet(workbook, expensesToExport);
            writeArPaymentsSheet(workbook);
            writeReceiptCustomizationsSheet(workbook);
            writeOrdersSheet(workbook);
            writeOrderItemsSheet(workbook);

            workbook.write(outputStream);
        }
    }

    private void writeProductsSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Products");
String[] headers = {"id", "sku", "name", "description", "category_id", "unit_price", "cost_price", "tax_rate", "stock_quantity", "reserved_quantity", "min_stock_level", "unit", "image_data", "image_type", "is_active", "deleted_at", "created_at", "updated_at"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (Product p : productRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, p.getId()); setCell(row, col++, p.getSku()); setCell(row, col++, p.getName());
            setCell(row, col++, p.getDescription()); setCell(row, col++, p.getCategory() != null ? p.getCategory().getId() : null);
            setCell(row, col++, p.getUnitPrice()); setCell(row, col++, p.getCostPrice()); setCell(row, col++, p.getTaxRate());
            setCell(row, col++, p.getStockQuantity());
            setCell(row, col++, p.getReservedQuantity()); setCell(row, col++, p.getMinStockLevel()); setCell(row, col++, p.getUnit());
            setCell(row, col++, binaryPlaceholder(p.getImageData())); setCell(row, col++, p.getImageType());
            setCell(row, col++, p.getIsActive()); setCell(row, col++, p.getDeletedAt()); setCell(row, col++, p.getCreatedAt()); setCell(row, col, p.getUpdatedAt());
        }
    }

    private void writeCategoriesSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Categories");
        String[] headers = {"id", "name", "description", "is_active", "deleted_at", "created_at", "updated_at"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (Category c : categoryRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, c.getId()); setCell(row, col++, c.getName()); setCell(row, col++, c.getDescription());
            setCell(row, col++, c.getIsActive()); setCell(row, col++, c.getDeletedAt()); setCell(row, col++, c.getCreatedAt()); setCell(row, col, c.getUpdatedAt());
        }
    }

    private void writeCustomersSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Customers");
        String[] headers = {"id", "customer_code", "first_name", "last_name", "email", "phone", "address", "city", "state", "zip_code", "country", "notes", "credit_limit", "current_balance", "is_active", "deleted_at", "created_at", "updated_at"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (Customer c : customerRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, c.getId()); setCell(row, col++, c.getCustomerCode()); setCell(row, col++, c.getFirstName());
            setCell(row, col++, c.getLastName()); setCell(row, col++, c.getEmail()); setCell(row, col++, c.getPhone());
            setCell(row, col++, c.getAddress()); setCell(row, col++, c.getCity()); setCell(row, col++, c.getState());
            setCell(row, col++, c.getZipCode()); setCell(row, col++, c.getCountry()); setCell(row, col++, c.getNotes());
            setCell(row, col++, c.getCreditLimit()); setCell(row, col++, c.getCurrentBalance());
            setCell(row, col++, c.getIsActive()); setCell(row, col++, c.getDeletedAt()); setCell(row, col++, c.getCreatedAt()); setCell(row, col, c.getUpdatedAt());
        }
    }

    private void writeSuppliersSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Suppliers");
        String[] headers = {"id", "name", "contact_person", "email", "phone", "address", "tax_id", "payment_terms", "notes", "is_active", "deleted_at", "created_at", "updated_at"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (Supplier s : supplierRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, s.getId()); setCell(row, col++, s.getName()); setCell(row, col++, s.getContactPerson());
            setCell(row, col++, s.getEmail()); setCell(row, col++, s.getPhone()); setCell(row, col++, s.getAddress());
            setCell(row, col++, s.getTaxId()); setCell(row, col++, s.getPaymentTerms()); setCell(row, col++, s.getNotes());
            setCell(row, col++, s.getIsActive()); setCell(row, col++, s.getDeletedAt()); setCell(row, col++, s.getCreatedAt()); setCell(row, col, s.getUpdatedAt());
        }
    }

    private void writeSalesSheet(Workbook workbook, List<Sale> sales) {
        Sheet sheet = workbook.createSheet("Sales");
        String[] headers = {"id", "invoice_number", "customer_id", "customer_display_name", "cashier_id", "sale_date", "subtotal", "tax_amount", "discount_amount", "total_amount", "amount_paid", "change_given", "payment_method", "sale_type", "payment_status", "due_date", "notes", "is_voided", "voided_reason", "voided_by", "voided_at", "is_active", "deleted_at", "created_at", "updated_at"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (Sale s : sales) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, s.getId()); setCell(row, col++, s.getInvoiceNumber());
            setCell(row, col++, s.getCustomer() != null ? s.getCustomer().getId() : null); setCell(row, col++, s.getCustomerDisplayName());
            setCell(row, col++, s.getCashierId());
            setCell(row, col++, s.getSaleDate()); setCell(row, col++, s.getSubtotal()); setCell(row, col++, s.getTaxAmount());
            setCell(row, col++, s.getDiscountAmount()); setCell(row, col++, s.getTotalAmount()); setCell(row, col++, s.getAmountPaid());
            setCell(row, col++, s.getChangeGiven()); setCell(row, col++, s.getPaymentMethod() != null ? s.getPaymentMethod().name() : null);
            setCell(row, col++, s.getSaleType() != null ? s.getSaleType().name() : null);
            setCell(row, col++, s.getPaymentStatus() != null ? s.getPaymentStatus().name() : null);
            setCell(row, col++, s.getDueDate());
            setCell(row, col++, s.getNotes()); setCell(row, col++, s.getIsVoided()); setCell(row, col++, s.getVoidedReason());
            setCell(row, col++, s.getVoidedBy()); setCell(row, col++, s.getVoidedAt()); setCell(row, col++, s.getIsActive());
            setCell(row, col++, s.getDeletedAt()); setCell(row, col++, s.getCreatedAt()); setCell(row, col, s.getUpdatedAt());
        }
    }

    private void writeSaleItemsSheet(Workbook workbook, List<Sale> filteredSales) {
        Sheet sheet = workbook.createSheet("Sale Items");
        String[] headers = {"id", "sale_id", "product_id", "quantity", "unit_price", "total_price", "tax_amount", "cost_price_at_sale", "quantity_refunded"};
        createHeaderRow(sheet, headers);

        // Only export items that belong to the filtered sales
        Set<Long> filteredSaleIds = filteredSales.stream().map(Sale::getId).collect(Collectors.toSet());

        int rowNum = 1;
        for (SaleItem item : saleItemRepository.findAll()) {
            Long saleId = item.getSale() != null ? item.getSale().getId() : null;
            if (filteredSaleIds.contains(saleId)) {
                Row row = sheet.createRow(rowNum++);
                int col = 0;
                setCell(row, col++, item.getId()); setCell(row, col++, saleId);
                setCell(row, col++, item.getProduct() != null ? item.getProduct().getId() : null);
                setCell(row, col++, item.getQuantity()); setCell(row, col++, item.getUnitPrice());
                setCell(row, col++, item.getTotalPrice()); setCell(row, col++, item.getTaxAmount());
                setCell(row, col++, item.getCostPriceAtSale()); setCell(row, col, item.getQuantityRefunded());
            }
        }
    }

    private void writePurchasesSheet(Workbook workbook, List<Purchase> purchases) {
        Sheet sheet = workbook.createSheet("Purchases");
        String[] headers = {"id", "purchase_number", "supplier_id", "purchase_date", "subtotal", "tax_amount", "total_amount", "discount_amount", "payment_status", "notes", "created_by", "is_active", "deleted_at", "created_at", "updated_at"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (Purchase p : purchases) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, p.getId()); setCell(row, col++, p.getPurchaseNumber());
            setCell(row, col++, p.getSupplier() != null ? p.getSupplier().getId() : null); setCell(row, col++, p.getPurchaseDate());
            setCell(row, col++, p.getSubtotal()); setCell(row, col++, p.getTaxAmount()); setCell(row, col++, p.getTotalAmount());
            setCell(row, col++, p.getDiscountAmount()); setCell(row, col++, p.getPaymentStatus() != null ? p.getPaymentStatus().name() : null);
            setCell(row, col++, p.getNotes()); setCell(row, col++, p.getCreatedBy()); setCell(row, col++, p.getIsActive());
            setCell(row, col++, p.getDeletedAt()); setCell(row, col++, p.getCreatedAt()); setCell(row, col, p.getUpdatedAt());
        }
    }

    private void writePurchaseItemsSheet(Workbook workbook, List<Purchase> filteredPurchases) {
        Sheet sheet = workbook.createSheet("Purchase Items");
        String[] headers = {"id", "purchase_id", "product_id", "quantity", "unit_cost", "total_cost"};
        createHeaderRow(sheet, headers);

        // Only export items that belong to the filtered purchases
        Set<Long> filteredPurchaseIds = filteredPurchases.stream().map(Purchase::getId).collect(Collectors.toSet());

        int rowNum = 1;
        for (PurchaseItem item : purchaseItemRepository.findAll()) {
            Long purchaseId = item.getPurchase() != null ? item.getPurchase().getId() : null;
            if (filteredPurchaseIds.contains(purchaseId)) {
                Row row = sheet.createRow(rowNum++);
                int col = 0;
                setCell(row, col++, item.getId()); setCell(row, col++, purchaseId);
                setCell(row, col++, item.getProduct() != null ? item.getProduct().getId() : null);
                setCell(row, col++, item.getQuantity()); setCell(row, col++, item.getUnitCost());
                setCell(row, col, item.getTotalCost());
            }
        }
    }

    private void writeExpensesSheet(Workbook workbook, List<Expense> expenses) {
        Sheet sheet = workbook.createSheet("Expenses");
        String[] headers = {"id", "category", "description", "amount", "expense_date", "created_by", "receipt_image", "receipt_image_type", "created_at", "deleted_at"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (Expense e : expenses) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, e.getId()); setCell(row, col++, e.getCategory() != null ? e.getCategory().name() : null);
            setCell(row, col++, e.getDescription()); setCell(row, col++, e.getAmount()); setCell(row, col++, e.getExpenseDate());
            setCell(row, col++, e.getCreatedBy()); setCell(row, col++, binaryPlaceholder(e.getReceiptImage()));
            setCell(row, col++, e.getReceiptImageType()); setCell(row, col++, e.getCreatedAt()); setCell(row, col, e.getDeletedAt());
        }
    }

    private void writeArPaymentsSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("AR Payments");
        String[] headers = {"id", "invoice_id", "amount", "payment_date", "recorded_by_id", "notes"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (ArPayment p : arPaymentRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, p.getId());
            setCell(row, col++, p.getInvoice() != null ? p.getInvoice().getId() : null);
            setCell(row, col++, p.getAmount());
            setCell(row, col++, p.getPaymentDate());
            setCell(row, col++, p.getRecordedBy() != null ? p.getRecordedBy().getId() : null);
            setCell(row, col, p.getNotes());
        }
    }

    private void writeReceiptCustomizationsSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Receipt Customizations");
        String[] headers = {"id", "header_text", "main_message", "footer_text", "paper_size", "time_format",
                "logo_size", "show_logo", "show_shop_name", "show_address", "show_phone", "header_align",
                "font_size", "divider_style", "bold_shop_name", "show_qr_code", "show_credit_info"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (ReceiptCustomization rc : receiptCustomizationRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, rc.getId()); setCell(row, col++, rc.getHeaderText()); setCell(row, col++, rc.getMainMessage());
            setCell(row, col++, rc.getFooterText()); setCell(row, col++, rc.getPaperSize()); setCell(row, col++, rc.getTimeFormat());
            setCell(row, col++, rc.getLogoSize()); setCell(row, col++, rc.getShowLogo()); setCell(row, col++, rc.getShowShopName());
            setCell(row, col++, rc.getShowAddress()); setCell(row, col++, rc.getShowPhone()); setCell(row, col++, rc.getHeaderAlign());
            setCell(row, col++, rc.getFontSize()); setCell(row, col++, rc.getDividerStyle()); setCell(row, col++, rc.getBoldShopName());
            setCell(row, col++, rc.getShowQRCode()); setCell(row, col, rc.getShowCreditInfo());
        }
    }

    private void writeOrdersSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Orders");
        String[] headers = {"id", "order_number", "customer_id", "customer_display_name", "cashier_id", "created_at", "subtotal", "tax_amount", "total_amount", "status", "converted_sale_id", "converted_at", "cancelled_at", "cancelled_by", "cancel_reason", "notes", "is_active", "deleted_at", "updated_at"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (Order o : orderRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, o.getId()); setCell(row, col++, o.getOrderNumber());
            setCell(row, col++, o.getCustomer() != null ? o.getCustomer().getId() : null);
            setCell(row, col++, o.getCustomerDisplayName());
            setCell(row, col++, o.getCashierId());
            setCell(row, col++, o.getCreatedAt());
            setCell(row, col++, o.getSubtotal()); setCell(row, col++, o.getTaxAmount()); setCell(row, col++, o.getTotalAmount());
            setCell(row, col++, o.getStatus() != null ? o.getStatus().name() : null);
            setCell(row, col++, o.getConvertedSaleId()); setCell(row, col++, o.getConvertedAt());
            setCell(row, col++, o.getCancelledAt()); setCell(row, col++, o.getCancelledBy()); setCell(row, col++, o.getCancelReason());
            setCell(row, col++, o.getNotes()); setCell(row, col++, o.getIsActive());
            setCell(row, col++, o.getDeletedAt()); setCell(row, col, o.getUpdatedAt());
        }
    }

    private void writeOrderItemsSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Order Items");
        String[] headers = {"id", "order_id", "product_id", "quantity", "unit_price", "total_price", "tax_amount", "cost_price_at_order"};
        createHeaderRow(sheet, headers);
        int rowNum = 1;
        for (Order o : orderRepository.findAll()) {
            Long orderId = o.getId();
            for (OrderItem item : o.getItems()) {
                Row row = sheet.createRow(rowNum++);
                int col = 0;
                setCell(row, col++, item.getId()); setCell(row, col++, orderId);
                setCell(row, col++, item.getProduct() != null ? item.getProduct().getId() : null);
                setCell(row, col++, item.getQuantity()); setCell(row, col++, item.getUnitPrice());
                setCell(row, col++, item.getTotalPrice()); setCell(row, col++, item.getTaxAmount());
                setCell(row, col, item.getCostPriceAtOrder());
            }
        }
    }

    private void createHeaderRow(Sheet sheet, String[] headers) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            headerRow.createCell(i).setCellValue(headers[i]);
        }
    }

    private void setCell(Row row, int col, Object value) {
        if (value == null) return;
        if (value instanceof Number number) {
            row.createCell(col).setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            row.createCell(col).setCellValue(bool);
        } else if (value instanceof LocalDateTime ldt) {
            row.createCell(col).setCellValue(ldt.format(DATETIME_FMT));
        } else if (value instanceof LocalDate ld) {
            row.createCell(col).setCellValue(ld.format(DATE_FMT));
        } else {
            row.createCell(col).setCellValue(value.toString());
        }
    }

    private String binaryPlaceholder(byte[] data) {
        if (data == null || data.length == 0) return "";
        return "[binary, " + data.length + " bytes]";
    }

    /**
     * Runs a backup and ALWAYS records the outcome in the settings so the UI can
     * show a green/red status instead of failing silently. Schedules a slow retry
     * (1 hour) after a failure so a persistent problem doesn't hammer Google.
     * Dissabling automation when the OAuth token has been revoked so the user is
     * forced to reconnect instead of racking up errors nobody sees.
     *
     * NOT @Transactional deliberately: the FAILED status must survive the rethrow.
     * Repository saves below commit on their own, so the exception is re-thrown
     * for the caller without rolling back the recorded outcome.
     */
    public String runBackupNow(LocalDate startDate, LocalDate endDate) throws Exception {
        // The scheduler's own entry point: nobody is at the keyboard, so the copy
        // it writes is one retention is allowed to thin away.
        return runBackupNow(startDate, endDate, null, false);
    }

    /**
     * @param passphrase opens the file this run writes; see {@link BackupEncryption}.
     *                   Only a person at the keyboard has it, which is why the
     *                   scheduler calls the two-argument version with none.
     */
    public String runBackupNow(LocalDate startDate, LocalDate endDate, char[] passphrase) throws Exception {
        return runBackupNow(startDate, endDate, passphrase, true);
    }

    /**
     * @param manual true when a person asked for this copy rather than the schedule;
     *               it is what puts {@code _manual} in the file name, and what keeps
     *               the copy out of retention's counting.
     */
    public String runBackupNow(LocalDate startDate, LocalDate endDate, char[] passphrase,
                               boolean manual) throws Exception {
        try {
            String link = executeGoogleDriveBackup(startDate, endDate, passphrase, manual);
            return link;
        } catch (Exception e) {
            BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc()
                    .orElseGet(() -> backupSettingRepository.save(new BackupSetting()));
            setting.setBackupStatus("FAILED");
            String message = friendlyError(e);
            setting.setLastErrorMessage(message);
            setting.setLastBackupAttempt(shopTimeService.now());
            if (isRevokedToken(e)) {
                setting.setEnabled(false);
                setting.setNextBackupDate(null);
                setting.setLastErrorMessage("Google access was revoked or expired. Reconnect Google Drive in Settings — automatic backups are paused.");
            } else {
                setting.setNextBackupDate(shopTimeService.now().plusHours(1));
            }
            backupSettingRepository.save(setting);
            throw e;
        }
    }

    private boolean isRevokedToken(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("invalid_grant")) {
                return true;
            }
        }
        return false;
    }

    private String friendlyError(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank()) {
                return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
            }
        }
        return "Unknown backup error";
    }

    // --- SINGLE, CONSOLIDATED METHOD FOR BOTH FULL AND SCHEDULED BACKUPS ---

    /** {@code DB} / {@code JSON} / {@code BOTH}; see {@link #resolveBackupFormat()}. */
    static final String BACKUP_FORMAT_SETTING = "backup.format";
    private static final Set<String> BACKUP_FORMATS = Set.of("DB", "JSON", "BOTH");

    /**
     * Whether manual backups get sealed before they reach Google Drive. The switch
     * lives here; the passphrase it seals with never does — see
     * {@link BackupEncryption}.
     */
    static final String BACKUP_ENCRYPTION_SETTING = "backup.encryption.enabled";

    /**
     * Which backup the scheduler writes.
     *
     * <p>A raw database snapshot is the only backup that carries the accounting
     * ledgers, the closed periods and the invoice counters, so a shop on SQLite
     * gets one by default. Engines without {@code VACUUM INTO} cannot produce it,
     * so they fall back to the JSON export instead of failing every night.
     */
    public String resolveBackupFormat() {
        String configured = systemSettingRepository.findBySettingKey(BACKUP_FORMAT_SETTING)
                .map(SystemSetting::getSettingValue)
                .map(String::trim)
                .map(String::toUpperCase)
                .orElse(null);
        if (configured != null && BACKUP_FORMATS.contains(configured)) {
            return configured;
        }
        return snapshotService.isSupported() ? "DB" : "JSON";
    }

    /**
     * Whether this shop could take a whole-database backup at all. The settings
     * page needs it to tell the difference between "JSON is enough here" and
     * "JSON is all you are getting, and that is a loss".
     */
    public boolean isRawSnapshotSupported() {
        return snapshotService.isSupported();
    }

    @Transactional
    public String executeGoogleDriveBackup(LocalDate startDate, LocalDate endDate) throws Exception {
        return executeGoogleDriveBackup(startDate, endDate, null, false);
    }

    /**
     * Writes this shop's backup to Google Drive and records the outcome.
     *
     * @param passphrase the secret to seal the files with, or null for an
     *                   unencrypted copy — see {@link #shouldEncrypt(char[])}
     */
    @Transactional
    public String executeGoogleDriveBackup(LocalDate startDate, LocalDate endDate,
                                          char[] passphrase) throws Exception {
        // Only a person has a passphrase to type, so a run that carries one is a
        // run that person asked for.
        return executeGoogleDriveBackup(startDate, endDate, passphrase, true);
    }

    /**
     * @param manual true when somebody pressed the button, which names the files
     *               {@code _manual} so retention leaves them to the shop
     */
    @Transactional
    public String executeGoogleDriveBackup(LocalDate startDate, LocalDate endDate,
                                          char[] passphrase, boolean manual) throws Exception {
        BackupSetting setting = backupSettingRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> backupSettingRepository.save(new BackupSetting()));

        if (setting.getGoogleRefreshToken() == null || setting.getGoogleRefreshToken().isEmpty()) {
            throw new StateConflictException("Google Drive is not connected. Please authorize first.");
        }

        // Read before anything is uploaded: the next slot is counted from this, so however
        // long Drive takes today, the schedule keeps the hour it promised.
        LocalDateTime planned = setting.getNextBackupDate();

        String folderId = googleDriveService.getOrCreateBackupFolderId(googleDriveService.getDriveService());
        String format = resolveBackupFormat();
        LocalDateTime takenAt = shopTimeService.now();
        boolean encrypt = shouldEncrypt(passphrase);

        List<GoogleDriveService.DriveUpload> uploads = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        if (!format.equals("JSON")) {
            try {
                uploads.add(snapshotService.uploadSnapshotToDrive(
                        googleDriveService, folderId, takenAt, encrypt ? passphrase : null, manual));
            } catch (Exception ex) {
                failed.add("database snapshot: " + friendlyError(new Exception(ex)));
                // BOTH still owes the shop the export; DB alone has nothing left
                // to fall back to, so the failure is the backup's result.
                if (uploads.isEmpty() && !format.equals("BOTH")) {
                    throw ex;
                }
            }
        }
        if (!format.equals("DB")) {
            try {
                uploads.add(uploadJsonBackup(folderId, takenAt, encrypt ? passphrase : null, manual));
            } catch (Exception ex) {
                failed.add("data export: " + friendlyError(new Exception(ex)));
                if (uploads.isEmpty()) {
                    throw ex;
                }
            }
        }

        setting.setLastBackupDate(takenAt);
        setting.setLastBackupAttempt(takenAt);
        setting.setNextBackupDate(calculateNextBackupDate(setting, planned == null ? takenAt : planned));
        // The next run is worked out before the result is written down because a
        // shop whose custom text was accepted before the settings page learned to
        // refuse it gets its plan AND a note saying the plan is a daily one.
        List<String> problems = new ArrayList<>(failed);
        String scheduleNote = invalidCronWarning(setting, takenAt);
        if (scheduleNote != null) {
            problems.add(scheduleNote);
        }
        setting.setBackupStatus(failed.isEmpty() ? "SUCCESS" : "PARTIAL");
        setting.setLastErrorMessage(problems.isEmpty() ? null : String.join("; ", problems));
        backupSettingRepository.save(setting);

        // The folder is tidied after the result is recorded, not before: nothing a
        // shop cannot act on tonight should turn a backup that worked into one
        // that is reported as failed.
        List<String> written = uploads.stream().map(GoogleDriveService.DriveUpload::id).toList();
        try {
            googleDriveService.pruneOldBackups(folderId, retentionCountOf(setting), written);
        } catch (Exception ex) {
            log.warn("The old backups in Google Drive could not be listed for cleanup: {}",
                    friendlyError(new Exception(ex)));
        }

        return uploads.get(0).link();
    }

    /**
     * The number of Drive backups of each kind this shop keeps: its own choice
     * when it made one, the default when it never did, and never so small that
     * the history stops being one.
     */
    public static int retentionCountOf(BackupSetting setting) {
        Integer configured = setting.getRetentionCount();
        return configured == null ? DEFAULT_RETENTION_COUNT
                : Math.max(MINIMUM_RETENTION_COUNT, configured);
    }

    /**
     * The restorable JSON export, streamed to a temp file and uploaded as one
     * verified file.
     *
     * <p>The name carries the time as well as the date, because a shop that backs
     * up twice on the same day — once by hand before a change, once on the
     * schedule — must be able to tell the two apart in the restore list instead of
     * finding one of them quietly overwrote the other.
     */
    private GoogleDriveService.DriveUpload uploadJsonBackup(String folderId, LocalDateTime takenAt,
                                                           char[] passphrase, boolean manual) throws Exception {
        String fileName = GoogleDriveService.JSON_BACKUP_PREFIX + takenAt.format(JSON_STAMP)
                + (manual ? GoogleDriveService.MANUAL_MARKER : "") + ".json";
        Path tempFile = dataExportService.exportAllToJsonFile();
        try {
            if (passphrase == null) {
                return googleDriveService.uploadVerified(tempFile.toFile(), "application/json", folderId, fileName);
            }
            Path sealed = BackupEncryption.encrypt(tempFile, passphrase);
            try {
                return googleDriveService.uploadVerified(sealed.toFile(), "application/octet-stream", folderId,
                        fileName + BackupEncryption.ENCRYPTED_SUFFIX);
            } finally {
                Files.deleteIfExists(sealed);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Whether this run writes sealed files.
     *
     * <p>Both halves of the decision are needed. The switch says the shop wants
     * encryption; the passphrase says somebody is actually here to type it, which
     * an automatic run never has — the secret is not in the database, so a backup
     * taken by the scheduler at 02:00 is a plain one. That is the trade the
     * settings page spells out rather than a bug: an encrypted nightly backup
     * nobody can open is no backup at all.
     */
    public boolean shouldEncrypt(char[] passphrase) {
        return isEncryptionEnabled() && passphrase != null && passphrase.length > 0;
    }

    /** {@code true} when the shop wants the files it uploads sealed. */
    public boolean isEncryptionEnabled() {
        return systemSettingRepository.findBySettingKey(BACKUP_ENCRYPTION_SETTING)
                .map(SystemSetting::getSettingValue)
                .map(value -> "true".equalsIgnoreCase(value == null ? null : value.trim()))
                .orElse(false);
    }

    @Transactional
    public void setEncryptionEnabled(boolean enabled) {
        SystemSetting setting = systemSettingRepository.findBySettingKey(BACKUP_ENCRYPTION_SETTING)
                .orElseGet(() -> {
                    SystemSetting created = new SystemSetting();
                    created.setSettingKey(BACKUP_ENCRYPTION_SETTING);
                    created.setDataType(SystemSetting.DataType.BOOLEAN);
                    created.setDescription("Whether manual backups are encrypted before upload to Google Drive");
                    return created;
                });
        setting.setSettingValue(Boolean.toString(enabled));
        systemSettingRepository.save(setting);
    }

    /**
     * Computes the next scheduled backup time.
     *
     * <p>The other frequencies count from the slot that was PLANNED, never from the moment
     * a run finished: the scheduler only looks every ten minutes and a backup takes time of
     * its own, so counting from the finish slid a daily 02:00 backup later every single day
     * until it ran during opening hours. A slot already behind the clock — the server was
     * off — is moved forward in whole periods, so three days switched off costs one
     * catch-up run rather than three.
     *
     * <p>A monthly or yearly run is pinned to the day of the month the schedule was first
     * planned on, not to whatever day the previous answer landed on: adding a month to
     * Jan 31 gives Feb 28, and adding a month to that gives Mar 28 forever. The anchor is
     * re-pinned on every step, so the 31st comes back in March and a leap-year 29th comes
     * back with the next leap year.
     *
     * @param from the planned slot of the run that just happened, or the moment automation
     *             was switched on when there is no plan to count from
     */
    public LocalDateTime calculateNextBackupDate(BackupSetting setting, LocalDateTime from) {
        String frequency = setting.getFrequency() == null ? "WEEKLY" : setting.getFrequency().toUpperCase();
        LocalDateTime now = shopTimeService.now();
        if ("CUSTOM".equals(frequency)) {
            return nextCronRun(setting, from, now);
        }

        int anchorDay = anchorDayOf(setting, from);
        LocalDateTime next = plusPeriods(atScheduledTime(setting, from), frequency, 1);
        if (isAnchoredFrequency(frequency)) {
            next = onAnchorDay(next, anchorDay);
        }
        while (!next.isAfter(now)) {
            next = plusPeriods(next, frequency, 1);
            if (isAnchoredFrequency(frequency)) {
                next = onAnchorDay(next, anchorDay);
            }
        }
        return next;
    }

    /**
     * The next time the cron text fires after the shop's own clock.
     *
     * <p>Counting from the slot that was planned is what turned three days switched
     * off into three backups in a row: the answer stayed in the past, the scheduler
     * ran it, and planned another past time. So the search starts no earlier than
     * now, and an answer that is somehow still not in the future is looked for
     * again rather than handed back.
     *
     * <p>A text that will not parse, or that never fires again, has no next time to
     * give, and only a row saved before the settings page refused such text can
     * still hold one. The shop gets tomorrow rather than nothing — automation that
     * stops saying when it runs is worse than one that runs on the wrong day — said
     * out loud in the log and in {@code lastErrorMessage}.
     */
    private static LocalDateTime nextCronRun(BackupSetting setting, LocalDateTime planned, LocalDateTime now) {
        LocalDateTime from = planned.isAfter(now) ? planned : now;
        try {
            CronExpression cron = CronExpression.parse(setting.getCustomCronExpression());
            LocalDateTime next = cron.next(from);
            for (int retry = 0; retry < 5 && (next == null || !next.isAfter(now)); retry++) {
                next = next == null ? null : cron.next(next);
            }
            if (next != null && next.isAfter(now)) {
                return next;
            }
            log.warn("The custom backup schedule [{}] has no next run; using a daily backup instead",
                    setting.getCustomCronExpression());
        } catch (Exception e) {
            log.warn("The custom backup schedule [{}] cannot be read; using a daily backup instead: {}",
                    setting.getCustomCronExpression(), e.getMessage());
        }
        return now.plusDays(1);
    }

    /**
     * The shortest gap a custom schedule may ask for. A backup takes minutes and
     * uploads what it made; one promised every couple of minutes spends the shop's
     * evening re-uploading the same database and never finishes a schedule cycle.
     */
    public static final Duration MINIMUM_CRON_GAP = Duration.ofMinutes(15);

    public static final String INVALID_CRON_TEXT_MESSAGE = "That schedule text is not valid";
    public static final String TOO_OFTEN_CRON_TEXT_MESSAGE =
            "That schedule text runs more often than every 15 minutes";
    public static final String INVALID_CRON_MESSAGE = "Custom schedule is not valid; using daily";

    /** How many runs in a row the gap between them is watched over. */
    private static final int MAX_CRON_RUNS_WATCHED = 10;

    /**
     * Why this custom text must not be saved, or null when it may be.
     *
     * <p>The runs are watched rather than the text read for a pattern: the question
     * the shop is being refused over is how often it will really back up, and that
     * is what the expression says once the clock does the arithmetic.
     *
     * <p>A stretch of them, because the gaps are not always equal — "minute 0 and
     * minute 10 of every hour" is ten minutes apart half the time and fifty the
     * other half, and a pair of neighbours can land on the fifty.
     */
    public static String cronScheduleError(String expression, LocalDateTime from) {
        try {
            CronExpression cron = CronExpression.parse(expression);
            LocalDateTime previous = cron.next(from);
            if (previous == null) {
                return INVALID_CRON_TEXT_MESSAGE;
            }
            for (int run = 1; run < MAX_CRON_RUNS_WATCHED; run++) {
                LocalDateTime next = cron.next(previous);
                if (next == null) {
                    break;
                }
                if (Duration.between(previous, next).compareTo(MINIMUM_CRON_GAP) < 0) {
                    return TOO_OFTEN_CRON_TEXT_MESSAGE;
                }
                previous = next;
            }
            return null;
        } catch (Exception e) {
            return INVALID_CRON_TEXT_MESSAGE;
        }
    }

    /**
     * The note an already-stored row earns because its custom text cannot be
     * planned, or null when the row schedules itself honestly.
     *
     * <p>Only unreadable text is noted: a row that parses plans what it parsed,
     * and telling the shop its schedule is "not valid" while it runs on that
     * schedule is a lie of a different kind.
     */
    public static String invalidCronWarning(BackupSetting setting, LocalDateTime from) {
        if (!"CUSTOM".equalsIgnoreCase(setting.getFrequency())) {
            return null;
        }
        return INVALID_CRON_TEXT_MESSAGE.equals(cronScheduleError(setting.getCustomCronExpression(), from))
                ? INVALID_CRON_MESSAGE
                : null;
    }

    /** Frequencies that mean "this day of the month", so the day has to be remembered. */
    public static boolean isAnchoredFrequency(String frequency) {
        String name = frequency == null ? "" : frequency.toUpperCase();
        return "MONTHLY".equals(name) || "YEARLY".equals(name);
    }

    /**
     * The day a monthly or yearly run belongs to. A row saved before it was written
     * down is read from its own plan, which is the best answer available — the
     * alternative, taking the day of whatever the old sliding rule produced, is how
     * the 31st became the 28th.
     */
    private static int anchorDayOf(BackupSetting setting, LocalDateTime from) {
        Integer recorded = setting.getAnchorDayOfMonth();
        return recorded == null || recorded < 1 || recorded > 31 ? from.getDayOfMonth() : recorded;
    }

    /** The anchor day in the month the date moved into, or the last day it has. */
    private static LocalDateTime onAnchorDay(LocalDateTime date, int anchorDay) {
        return date.withDayOfMonth(Math.min(anchorDay, date.toLocalDate().lengthOfMonth()));
    }

    /** The hour the shop chose, or the hour automation started at when it chose none. */
    private static LocalDateTime atScheduledTime(BackupSetting setting, LocalDateTime slot) {
        LocalTime scheduled = setting.getScheduledTime();
        return scheduled == null ? slot : slot.with(scheduled);
    }

    private static LocalDateTime plusPeriods(LocalDateTime slot, String frequency, int periods) {
        return switch (frequency) {
            case "WEEKLY" -> slot.plusWeeks(periods);
            case "MONTHLY" -> slot.plusMonths(periods);
            case "YEARLY" -> slot.plusYears(periods);
            default -> slot.plusDays(periods);
        };
    }

    /**
     * Safely checks if a date object (LocalDate, LocalDateTime, or java.util.Date)
     * falls within the given startDate and endDate range.
     *
     * <p>An instant is read on the shop's clock: the range was typed against the
     * shop's calendar, and on the machine's clock a shop east of its server loses
     * the first hours of the last day it asked for.
     */
    private boolean isDateInRange(Object dateObject, LocalDate startDate, LocalDate endDate) {
        if (dateObject == null) return false;

        LocalDate date;
        if (dateObject instanceof java.time.LocalDateTime) {
            date = ((java.time.LocalDateTime) dateObject).toLocalDate();
        } else if (dateObject instanceof java.time.LocalDate) {
            date = (java.time.LocalDate) dateObject;
        } else if (dateObject instanceof java.util.Date) {
            date = ((java.util.Date) dateObject).toInstant()
                    .atZone(shopTimeService.zone()).toLocalDate();
        } else {
            return false; // Fallback for unknown types
        }

        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}