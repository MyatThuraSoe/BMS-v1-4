package com.bms.service;

import com.bms.entity.*;
import com.bms.repository.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class BackupService {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final PurchaseRepository purchaseRepository;
    private final PurchaseItemRepository purchaseItemRepository;
    private final ExpenseRepository expenseRepository;

    public BackupService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            CustomerRepository customerRepository,
            SupplierRepository supplierRepository,
            SaleRepository saleRepository,
            SaleItemRepository saleItemRepository,
            PurchaseRepository purchaseRepository,
            PurchaseItemRepository purchaseItemRepository,
            ExpenseRepository expenseRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
        this.saleRepository = saleRepository;
        this.saleItemRepository = saleItemRepository;
        this.purchaseRepository = purchaseRepository;
        this.purchaseItemRepository = purchaseItemRepository;
        this.expenseRepository = expenseRepository;
    }

    @Transactional(readOnly = true)
    public void exportFullBackup(OutputStream outputStream) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            writeProductsSheet(workbook);
            writeCategoriesSheet(workbook);
            writeCustomersSheet(workbook);
            writeSuppliersSheet(workbook);
            writeSalesSheet(workbook);
            writeSaleItemsSheet(workbook);
            writePurchasesSheet(workbook);
            writePurchaseItemsSheet(workbook);
            writeExpensesSheet(workbook);
            workbook.write(outputStream);
        }
    }

    private void writeProductsSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Products");
        String[] headers = {
                "id", "sku", "name", "description", "category_id", "unit_price", "cost_price",
                "tax_rate", "stock_quantity", "min_stock_level", "barcode", "image_data",
                "image_type", "is_active", "deleted_at", "created_at", "updated_at"
        };
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (Product p : productRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, p.getId());
            setCell(row, col++, p.getSku());
            setCell(row, col++, p.getName());
            setCell(row, col++, p.getDescription());
            setCell(row, col++, p.getCategory() != null ? p.getCategory().getId() : null);
            setCell(row, col++, p.getUnitPrice());
            setCell(row, col++, p.getCostPrice());
            setCell(row, col++, p.getTaxRate());
            setCell(row, col++, p.getStockQuantity());
            setCell(row, col++, p.getMinStockLevel());
            setCell(row, col++, p.getBarcode());
            setCell(row, col++, binaryPlaceholder(p.getImageData()));
            setCell(row, col++, p.getImageType());
            setCell(row, col++, p.getIsActive());
            setCell(row, col++, p.getDeletedAt());
            setCell(row, col++, p.getCreatedAt());
            setCell(row, col, p.getUpdatedAt());
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
            setCell(row, col++, c.getId());
            setCell(row, col++, c.getName());
            setCell(row, col++, c.getDescription());
            setCell(row, col++, c.getIsActive());
            setCell(row, col++, c.getDeletedAt());
            setCell(row, col++, c.getCreatedAt());
            setCell(row, col, c.getUpdatedAt());
        }
    }

    private void writeCustomersSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Customers");
        String[] headers = {
                "id", "customer_code", "first_name", "last_name", "email", "phone", "address",
                "city", "state", "zip_code", "country", "notes", "is_active", "deleted_at",
                "created_at", "updated_at"
        };
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (Customer c : customerRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, c.getId());
            setCell(row, col++, c.getCustomerCode());
            setCell(row, col++, c.getFirstName());
            setCell(row, col++, c.getLastName());
            setCell(row, col++, c.getEmail());
            setCell(row, col++, c.getPhone());
            setCell(row, col++, c.getAddress());
            setCell(row, col++, c.getCity());
            setCell(row, col++, c.getState());
            setCell(row, col++, c.getZipCode());
            setCell(row, col++, c.getCountry());
            setCell(row, col++, c.getNotes());
            setCell(row, col++, c.getIsActive());
            setCell(row, col++, c.getDeletedAt());
            setCell(row, col++, c.getCreatedAt());
            setCell(row, col, c.getUpdatedAt());
        }
    }

    private void writeSuppliersSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Suppliers");
        String[] headers = {
                "id", "name", "contact_person", "email", "phone", "address", "tax_id",
                "payment_terms", "notes", "is_active", "deleted_at", "created_at", "updated_at"
        };
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (Supplier s : supplierRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, s.getId());
            setCell(row, col++, s.getName());
            setCell(row, col++, s.getContactPerson());
            setCell(row, col++, s.getEmail());
            setCell(row, col++, s.getPhone());
            setCell(row, col++, s.getAddress());
            setCell(row, col++, s.getTaxId());
            setCell(row, col++, s.getPaymentTerms());
            setCell(row, col++, s.getNotes());
            setCell(row, col++, s.getIsActive());
            setCell(row, col++, s.getDeletedAt());
            setCell(row, col++, s.getCreatedAt());
            setCell(row, col, s.getUpdatedAt());
        }
    }

    private void writeSalesSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Sales");
        String[] headers = {
                "id", "invoice_number", "customer_id", "cashier_id", "sale_date", "subtotal",
                "tax_amount", "discount_amount", "total_amount", "amount_paid", "change_given",
                "payment_method", "notes", "is_voided", "voided_reason", "voided_by", "voided_at",
                "is_active", "deleted_at", "created_at", "updated_at"
        };
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (Sale s : saleRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, s.getId());
            setCell(row, col++, s.getInvoiceNumber());
            setCell(row, col++, s.getCustomer() != null ? s.getCustomer().getId() : null);
            setCell(row, col++, s.getCashierId());
            setCell(row, col++, s.getSaleDate());
            setCell(row, col++, s.getSubtotal());
            setCell(row, col++, s.getTaxAmount());
            setCell(row, col++, s.getDiscountAmount());
            setCell(row, col++, s.getTotalAmount());
            setCell(row, col++, s.getAmountPaid());
            setCell(row, col++, s.getChangeGiven());
            setCell(row, col++, s.getPaymentMethod() != null ? s.getPaymentMethod().name() : null);
            setCell(row, col++, s.getNotes());
            setCell(row, col++, s.getIsVoided());
            setCell(row, col++, s.getVoidedReason());
            setCell(row, col++, s.getVoidedBy());
            setCell(row, col++, s.getVoidedAt());
            setCell(row, col++, s.getIsActive());
            setCell(row, col++, s.getDeletedAt());
            setCell(row, col++, s.getCreatedAt());
            setCell(row, col, s.getUpdatedAt());
        }
    }

    private void writeSaleItemsSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Sale Items");
        String[] headers = {
                "id", "sale_id", "product_id", "quantity", "unit_price", "total_price",
                "tax_amount", "cost_price_at_sale", "quantity_refunded"
        };
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (SaleItem item : saleItemRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, item.getId());
            setCell(row, col++, item.getSale() != null ? item.getSale().getId() : null);
            setCell(row, col++, item.getProduct() != null ? item.getProduct().getId() : null);
            setCell(row, col++, item.getQuantity());
            setCell(row, col++, item.getUnitPrice());
            setCell(row, col++, item.getTotalPrice());
            setCell(row, col++, item.getTaxAmount());
            setCell(row, col++, item.getCostPriceAtSale());
            setCell(row, col, item.getQuantityRefunded());
        }
    }

    private void writePurchasesSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Purchases");
        String[] headers = {
                "id", "purchase_number", "supplier_id", "purchase_date", "subtotal", "tax_amount",
                "total_amount", "discount_amount", "payment_status", "notes", "created_by",
                "is_active", "deleted_at", "created_at", "updated_at"
        };
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (Purchase p : purchaseRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, p.getId());
            setCell(row, col++, p.getPurchaseNumber());
            setCell(row, col++, p.getSupplier() != null ? p.getSupplier().getId() : null);
            setCell(row, col++, p.getPurchaseDate());
            setCell(row, col++, p.getSubtotal());
            setCell(row, col++, p.getTaxAmount());
            setCell(row, col++, p.getTotalAmount());
            setCell(row, col++, p.getDiscountAmount());
            setCell(row, col++, p.getPaymentStatus() != null ? p.getPaymentStatus().name() : null);
            setCell(row, col++, p.getNotes());
            setCell(row, col++, p.getCreatedBy());
            setCell(row, col++, p.getIsActive());
            setCell(row, col++, p.getDeletedAt());
            setCell(row, col++, p.getCreatedAt());
            setCell(row, col, p.getUpdatedAt());
        }
    }

    private void writePurchaseItemsSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Purchase Items");
        String[] headers = {"id", "purchase_id", "product_id", "quantity", "unit_cost", "total_cost"};
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (PurchaseItem item : purchaseItemRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, item.getId());
            setCell(row, col++, item.getPurchase() != null ? item.getPurchase().getId() : null);
            setCell(row, col++, item.getProduct() != null ? item.getProduct().getId() : null);
            setCell(row, col++, item.getQuantity());
            setCell(row, col++, item.getUnitCost());
            setCell(row, col, item.getTotalCost());
        }
    }

    private void writeExpensesSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Expenses");
        String[] headers = {
                "id", "category", "description", "amount", "expense_date", "created_by",
                "receipt_image", "receipt_image_type", "created_at", "deleted_at"
        };
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (Expense e : expenseRepository.findAll()) {
            Row row = sheet.createRow(rowNum++);
            int col = 0;
            setCell(row, col++, e.getId());
            setCell(row, col++, e.getCategory() != null ? e.getCategory().name() : null);
            setCell(row, col++, e.getDescription());
            setCell(row, col++, e.getAmount());
            setCell(row, col++, e.getExpenseDate());
            setCell(row, col++, e.getCreatedBy());
            setCell(row, col++, binaryPlaceholder(e.getReceiptImage()));
            setCell(row, col++, e.getReceiptImageType());
            setCell(row, col++, e.getCreatedAt());
            setCell(row, col, e.getDeletedAt());
        }
    }

    private void createHeaderRow(Sheet sheet, String[] headers) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            headerRow.createCell(i).setCellValue(headers[i]);
        }
    }

    private void setCell(Row row, int col, Object value) {
        if (value == null) {
            return;
        }
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
        if (data == null || data.length == 0) {
            return "";
        }
        return "[binary, " + data.length + " bytes]";
    }
}
