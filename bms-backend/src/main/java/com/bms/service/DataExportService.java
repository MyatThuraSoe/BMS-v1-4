package com.bms.service;

import com.bms.entity.*;
import com.bms.repository.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class DataExportService {

    /**
     * Rows per read. A backup must not need the whole table in memory: the shop
     * that has 300k sales is exactly the shop that needs the backup.
     */
    static final int PAGE_SIZE = 500;

    @Autowired
    private ShopTimeService shopTimeService;

    @Autowired
    private InstallIdentity installIdentity;

    @PersistenceContext
    private EntityManager entityManager;

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final SaleRepository saleRepository;
    private final PurchaseRepository purchaseRepository;
    private final ArPaymentRepository arPaymentRepository;
    private final ReceiptCustomizationRepository receiptCustomizationRepository;
    private final OrderRepository orderRepository;
    private final OrderSequenceRepository orderSequenceRepository;
    private final SaleReturnRepository saleReturnRepository;
    private final ExpenseRepository expenseRepository;
    private final CashShiftRepository cashShiftRepository;
    private final UserRepository userRepository;
    private final ShopInfoRepository shopInfoRepository;
    private final SystemSettingRepository systemSettingRepository;

    private final InvoiceSequenceRepository invoiceSequenceRepository;
    private final PurchaseSequenceRepository purchaseSequenceRepository;
    private final CustomerSequenceRepository customerSequenceRepository;
    private final TransferSequenceRepository transferSequenceRepository;
    private final LocationRepository locationRepository;
    private final LocationStockRepository locationStockRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockTransferRepository stockTransferRepository;
    private final PriceListRepository priceListRepository;
    private final PromotionRepository promotionRepository;
    private final ProductPriceHistoryRepository productPriceHistoryRepository;
    private final PurchasePaymentRepository purchasePaymentRepository;
    private final CashDrawerRepository cashDrawerRepository;
    private final CashDrawerSessionRepository cashDrawerSessionRepository;
    private final CashDrawerMovementRepository cashDrawerMovementRepository;
    private final CountSheetRepository countSheetRepository;
    private final GlAccountRepository glAccountRepository;
    private final GlMappingRepository glMappingRepository;
    private final PeriodCloseRepository periodCloseRepository;
    private final InventoryLedgerRepository inventoryLedgerRepository;
    private final ProfitLedgerRepository profitLedgerRepository;

    /**
     * Serializes INSIDE the transaction so lazy collections load correctly.
     */
    @Transactional(readOnly = true)
    public byte[] exportAllAsJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writeBackup(out);
        } catch (Exception e) {
            throw new RuntimeException("Export failed: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    /**
     * The same document, streamed straight into a temp file. This is what the
     * scheduled Drive backup uses so a large shop never has to hold its whole
     * history in memory twice.
     */
    @Transactional(readOnly = true)
    public Path exportAllToJsonFile() throws IOException {
        Path target = Files.createTempFile("lumipos_backup_", ".json");
        Files.deleteIfExists(target);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            writeBackup(out);
        } catch (Exception e) {
            Files.deleteIfExists(target);
            throw new IOException("Export failed: " + e.getMessage(), e);
        }
        return target;
    }

    private void writeBackup(OutputStream out) throws IOException {
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()   // handles LocalDateTime
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        try (JsonGenerator gen = mapper.getFactory().createGenerator(out)) {
            gen.useDefaultPrettyPrinter();
            gen.writeStartObject();
            gen.writeStringField("app", "LumiPOS");
            gen.writeStringField("backupVersion", "1.0");
            // Which shop this came out of. A merge is only safe when the ids in the
            // file mean the ids here, and this is the one field that says so. Files
            // written before it existed simply have no key, and a merge refuses
            // them until somebody says the file is this shop's own.
            gen.writeStringField("installId", installIdentity.currentId());
            gen.writeStringField("exportedAt", shopTimeService.now().toString());
            gen.writeFieldName("data");
            gen.writeStartObject();
            writeSections(gen, mapper);
            gen.writeEndObject();
            gen.writeEndObject();
        }
    }

    private void writeSections(JsonGenerator gen, ObjectMapper mapper) throws IOException {
        writeEntities(gen, mapper, "categories", categoryRepository);
        writeEntities(gen, mapper, "products", productRepository);
        writeEntities(gen, mapper, "customers", customerRepository);
        writeEntities(gen, mapper, "suppliers", supplierRepository);
        writeEntities(gen, mapper, "sales", saleRepository);
        writeEntities(gen, mapper, "purchases", purchaseRepository);
        writeRows(gen, mapper, "saleReturns", saleReturnRepository, this::saleReturnRow);
        writeEntities(gen, mapper, "expenses", expenseRepository);
        writeEntities(gen, mapper, "cashShifts", cashShiftRepository);
        writeRows(gen, mapper, "users", userRepository, this::userRow);
        writeEntities(gen, mapper, "shopInfo", shopInfoRepository);
        // The install id is written once, as the file's own field. As a setting it
        // would be a second copy an import could disagree with, and it is not
        // something a shop should be able to edit or carry from one install to
        // another.
        writeSection(gen, "systemSettings", systemSettingRepository, "id", setting -> {
            // A secret that leaves in a backup file is a secret that lives on
            // Google Drive; this install's print agent token is not exportable.
            if (!InstallIdentity.SETTING_KEY.equals(setting.getSettingKey())
                    && !SystemSettingService.SECRET_KEYS.contains(setting.getSettingKey())) {
                mapper.writeValue(gen, setting);
            }
        });
        writeRows(gen, mapper, "arPayments", arPaymentRepository, this::arPaymentRow);
        writeEntities(gen, mapper, "receiptCustomizations", receiptCustomizationRepository);
        writeRows(gen, mapper, "orders", orderRepository, this::orderRow);
        writeEntities(gen, mapper, "orderSequences", orderSequenceRepository);

        // Invoice/purchase/customer/transfer numbering: without these a restored
        // shop starts handing out invoice numbers it already gave away.
        writeEntities(gen, mapper, "invoiceSequences", invoiceSequenceRepository);
        writeEntities(gen, mapper, "purchaseSequences", purchaseSequenceRepository);
        writeEntities(gen, mapper, "customerSequences", customerSequenceRepository);
        writeEntities(gen, mapper, "transferSequences", transferSequenceRepository);

        writeEntities(gen, mapper, "locations", locationRepository);
        writeRows(gen, mapper, "locationStock", locationStockRepository, this::locationStockRow);
        writeRows(gen, mapper, "stockMovements", stockMovementRepository, this::stockMovementRow);
        writeRows(gen, mapper, "stockTransfers", stockTransferRepository, this::stockTransferRow);
        writeRows(gen, mapper, "priceLists", priceListRepository, this::priceListRow);
        writeRows(gen, mapper, "promotions", promotionRepository, this::promotionRow);
        writeRows(gen, mapper, "productPriceHistory", productPriceHistoryRepository,
                this::productPriceHistoryRow);
        writeRows(gen, mapper, "purchasePayments", purchasePaymentRepository, this::purchasePaymentRow);

        writeEntities(gen, mapper, "cashDrawers", cashDrawerRepository);
        writeRows(gen, mapper, "cashDrawerSessions", cashDrawerSessionRepository,
                this::cashDrawerSessionRow);
        writeRows(gen, mapper, "cashDrawerMovements", cashDrawerMovementRepository,
                this::cashDrawerMovementRow);

        writeRows(gen, mapper, "countSheets", countSheetRepository, this::countSheetRow);

        writeEntities(gen, mapper, "glAccounts", glAccountRepository);
        writeRows(gen, mapper, "glMappings", glMappingRepository, this::glMappingRow);

        // The accounting history: a JSON backup that leaves these out cannot be
        // restored over a closed period, because the closes and the journals that
        // justify them would be gone.
        writeEntities(gen, mapper, "periodCloses", periodCloseRepository, "period");
        writeEntities(gen, mapper, "inventoryLedger", inventoryLedgerRepository);
        writeEntities(gen, mapper, "profitLedger", profitLedgerRepository);
    }

    // ------------------------------------------------------------------ writing

    private interface RowWriter<T> {
        void write(T row) throws IOException;
    }

    private <T> void writeEntities(JsonGenerator gen, ObjectMapper mapper, String section,
                                   JpaRepository<T, ?> repo) throws IOException {
        writeEntities(gen, mapper, section, repo, "id");
    }

    private <T> void writeEntities(JsonGenerator gen, ObjectMapper mapper, String section,
                                   JpaRepository<T, ?> repo, String sortProperty) throws IOException {
        writeSection(gen, section, repo, sortProperty, row -> mapper.writeValue(gen, row));
    }

    private <T> void writeRows(JsonGenerator gen, ObjectMapper mapper, String section,
                               JpaRepository<T, ?> repo,
                               Function<T, Map<String, Object>> flatten) throws IOException {
        writeSection(gen, section, repo, "id",
                row -> mapper.writeValue(gen, flatten.apply(row)));
    }

    /**
     * Writes one section as a JSON array, reading the table in pages and dropping
     * each page from the persistence context once it has been written. The sort is
     * required, not cosmetic: paged reads without an order can repeat or skip rows.
     */
    private <T> void writeSection(JsonGenerator gen, String section, JpaRepository<T, ?> repo,
                                  String sortProperty, RowWriter<T> writer) throws IOException {
        gen.writeArrayFieldStart(section);
        int page = 0;
        List<T> rows;
        do {
            rows = repo.findAll(PageRequest.of(page++, PAGE_SIZE, Sort.by(sortProperty))).getContent();
            for (T row : rows) {
                writer.write(row);
            }
            entityManager.clear();
        } while (rows.size() == PAGE_SIZE);
        gen.writeEndArray();
    }

    // ------------------------------------------------------------------ flat rows

    /** Sale returns are flattened (sale_id / returned_by / sale_item_id only),
     *  and the refund's own money and drawer trail travels with it. */
    private Map<String, Object> saleReturnRow(SaleReturn r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("saleId", r.getSale() != null ? r.getSale().getId() : null);
        row.put("returnedById", r.getReturnedBy() != null ? r.getReturnedBy().getId() : null);
        row.put("returnDate", r.getReturnDate());
        row.put("reason", r.getReason());
        row.put("totalReturnAmount", r.getTotalReturnAmount());
        row.put("debtReductionAmount", r.getDebtReductionAmount());
        row.put("refundAmount", r.getRefundAmount());
        row.put("refundMethod", r.getRefundMethod());
        row.put("employeeShiftId", r.getEmployeeShift() != null ? r.getEmployeeShift().getId() : null);
        row.put("drawerMovementId", r.getDrawerMovement() != null ? r.getDrawerMovement().getId() : null);
        row.put("externalReference", r.getExternalReference());
        List<Map<String, Object>> items = new ArrayList<>();
        if (r.getItems() != null) {
            for (SaleReturnItem it : r.getItems()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", it.getId());
                item.put("saleItemId", it.getSaleItem() != null ? it.getSaleItem().getId() : null);
                item.put("quantityReturned", it.getQuantityReturned());
                item.put("returnAmount", it.getReturnAmount());
                items.add(item);
            }
        }
        row.put("items", items);
        return row;
    }

    /**
     * Users are flattened so the password hash travels with the backup but Jackson
     * never re-serializes the UserDetails/security properties. The role is exported
     * by name (roles table is never wiped/restored).
     */
    private Map<String, Object> userRow(User u) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", u.getId());
        row.put("username", u.getUsername());
        row.put("email", u.getEmail());
        row.put("password", u.getPassword());
        row.put("firstName", u.getFirstName());
        row.put("lastName", u.getLastName());
        row.put("phone", u.getPhone());
        row.put("roleName", u.getRole() != null && u.getRole().getName() != null
                ? u.getRole().getName().name() : null);
        row.put("isActive", u.getIsActive());
        row.put("deletedAt", u.getDeletedAt());
        row.put("preferredLanguage", u.getPreferredLanguage());
        row.put("createdAt", u.getCreatedAt());
        row.put("updatedAt", u.getUpdatedAt());
        return row;
    }

    /** AR payments are flattened (invoice / recorder / shift / drawer movement by
     *  id) so the associations never pull in nested Sale/User objects, and so the
     *  cash trail a payment left in the drawer travels with it. */
    private Map<String, Object> arPaymentRow(ArPayment p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("invoiceId", p.getInvoice() != null ? p.getInvoice().getId() : null);
        row.put("amount", p.getAmount());
        row.put("paymentMethod", p.getPaymentMethod());
        row.put("paymentDate", p.getPaymentDate());
        row.put("recordedById", p.getRecordedBy() != null ? p.getRecordedBy().getId() : null);
        row.put("employeeShiftId", p.getEmployeeShift() != null ? p.getEmployeeShift().getId() : null);
        row.put("drawerMovementId", p.getDrawerMovement() != null ? p.getDrawerMovement().getId() : null);
        row.put("reversedAt", p.getReversedAt());
        row.put("reversalNote", p.getReversalNote());
        row.put("notes", p.getNotes());
        return row;
    }

    /** Orders are flattened (customer_id / product_id / cashier_id only) so the
     *  associations never pull in nested Customer/Product objects. */
    private Map<String, Object> orderRow(Order o) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", o.getId());
        row.put("orderNumber", o.getOrderNumber());
        row.put("customerId", o.getCustomer() != null ? o.getCustomer().getId() : null);
        row.put("customerDisplayName", o.getCustomerDisplayName());
        row.put("cashierId", o.getCashierId());
        row.put("createdAt", o.getCreatedAt());
        row.put("subtotal", o.getSubtotal());
        row.put("taxAmount", o.getTaxAmount());
        row.put("totalAmount", o.getTotalAmount());
        row.put("status", o.getStatus() != null ? o.getStatus().name() : null);
        row.put("convertedSaleId", o.getConvertedSaleId());
        row.put("convertedAt", o.getConvertedAt());
        row.put("cancelledAt", o.getCancelledAt());
        row.put("cancelledBy", o.getCancelledBy());
        row.put("cancelReason", o.getCancelReason());
        row.put("notes", o.getNotes());
        row.put("isActive", o.getIsActive());
        row.put("deletedAt", o.getDeletedAt());
        row.put("updatedAt", o.getUpdatedAt());
        List<Map<String, Object>> items = new ArrayList<>();
        if (o.getItems() != null) {
            for (OrderItem it : o.getItems()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", it.getId());
                item.put("productId", it.getProduct() != null ? it.getProduct().getId() : null);
                item.put("quantity", it.getQuantity());
                item.put("unitPrice", it.getUnitPrice());
                item.put("totalPrice", it.getTotalPrice());
                item.put("taxAmount", it.getTaxAmount());
                item.put("costPriceAtOrder", it.getCostPriceAtOrder());
                items.add(item);
            }
        }
        row.put("items", items);
        return row;
    }

    private Map<String, Object> locationStockRow(LocationStock s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("locationId", s.getLocation() != null ? s.getLocation().getId() : null);
        row.put("productId", s.getProduct() != null ? s.getProduct().getId() : null);
        row.put("quantity", s.getQuantity());
        row.put("updatedAt", s.getUpdatedAt());
        return row;
    }

    private Map<String, Object> stockMovementRow(StockMovement m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.getId());
        row.put("productId", m.getProduct() != null ? m.getProduct().getId() : null);
        row.put("movementType", name(m.getMovementType()));
        row.put("quantity", m.getQuantity());
        row.put("referenceType", name(m.getReferenceType()));
        row.put("referenceId", m.getReferenceId());
        row.put("purchaseItemId", m.getPurchaseItemId());
        row.put("sourceTransferId", m.getSourceTransferId());
        row.put("sourceSaleId", m.getSourceSaleId());
        row.put("sourceReturnId", m.getSourceReturnId());
        row.put("description", m.getDescription());
        row.put("reasonCode", m.getReasonCode());
        row.put("location", m.getLocation());
        row.put("valuationAmount", m.getValuationAmount());
        row.put("createdById", m.getCreatedBy() != null ? m.getCreatedBy().getId() : null);
        row.put("movementDate", m.getMovementDate());
        row.put("createdAt", m.getCreatedAt());
        return row;
    }

    private Map<String, Object> stockTransferRow(StockTransfer t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", t.getId());
        row.put("transferNumber", t.getTransferNumber());
        row.put("fromLocationId", t.getFromLocation() != null ? t.getFromLocation().getId() : null);
        row.put("toLocationId", t.getToLocation() != null ? t.getToLocation().getId() : null);
        row.put("status", name(t.getStatus()));
        row.put("notes", t.getNotes());
        row.put("createdBy", t.getCreatedBy());
        row.put("completedBy", t.getCompletedBy());
        row.put("createdAt", t.getCreatedAt());
        row.put("completedAt", t.getCompletedAt());
        List<Map<String, Object>> items = new ArrayList<>();
        if (t.getItems() != null) {
            for (StockTransferItem it : t.getItems()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", it.getId());
                item.put("productId", it.getProduct() != null ? it.getProduct().getId() : null);
                item.put("quantity", it.getQuantity());
                items.add(item);
            }
        }
        row.put("items", items);
        return row;
    }

    private Map<String, Object> priceListRow(PriceList p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("name", p.getName());
        row.put("description", p.getDescription());
        row.put("validFrom", p.getValidFrom());
        row.put("validTo", p.getValidTo());
        row.put("isActive", p.getIsActive());
        row.put("createdAt", p.getCreatedAt());
        row.put("updatedAt", p.getUpdatedAt());
        List<Map<String, Object>> items = new ArrayList<>();
        if (p.getItems() != null) {
            for (PriceListItem it : p.getItems()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", it.getId());
                item.put("productId", it.getProduct() != null ? it.getProduct().getId() : null);
                item.put("price", it.getPrice());
                items.add(item);
            }
        }
        row.put("items", items);
        return row;
    }

    private Map<String, Object> promotionRow(Promotion p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("name", p.getName());
        row.put("description", p.getDescription());
        row.put("type", name(p.getType()));
        row.put("scope", name(p.getScope()));
        row.put("productId", p.getProduct() != null ? p.getProduct().getId() : null);
        row.put("categoryId", p.getCategory() != null ? p.getCategory().getId() : null);
        row.put("buyQuantity", p.getBuyQuantity());
        row.put("getQuantity", p.getGetQuantity());
        row.put("minQuantity", p.getMinQuantity());
        row.put("discountValue", p.getDiscountValue());
        row.put("startDate", p.getStartDate());
        row.put("endDate", p.getEndDate());
        row.put("priority", p.getPriority());
        row.put("isActive", p.getIsActive());
        row.put("createdAt", p.getCreatedAt());
        row.put("updatedAt", p.getUpdatedAt());
        List<Map<String, Object>> tiers = new ArrayList<>();
        if (p.getTiers() != null) {
            for (PromotionTier tier : p.getTiers()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", tier.getId());
                t.put("minQuantity", tier.getMinQuantity());
                t.put("discountPercent", tier.getDiscountPercent());
                tiers.add(t);
            }
        }
        row.put("tiers", tiers);
        return row;
    }

    private Map<String, Object> productPriceHistoryRow(ProductPriceHistory h) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", h.getId());
        row.put("productId", h.getProduct() != null ? h.getProduct().getId() : null);
        row.put("oldPrice", h.getOldPrice());
        row.put("newPrice", h.getNewPrice());
        row.put("changedById", h.getChangedBy() != null ? h.getChangedBy().getId() : null);
        row.put("purchaseSupplierName", h.getPurchaseSupplierName());
        row.put("purchaseQuantity", h.getPurchaseQuantity());
        row.put("purchaseUnitPrice", h.getPurchaseUnitPrice());
        row.put("changedAt", h.getChangedAt());
        row.put("priceType", name(h.getPriceType()));
        row.put("source", name(h.getSource()));
        row.put("reason", h.getReason());
        row.put("purchaseId", h.getPurchaseId());
        row.put("supplierId", h.getSupplierId());
        row.put("stockQtyAtChange", h.getStockQtyAtChange());
        row.put("inventoryValueDelta", h.getInventoryValueDelta());
        return row;
    }

    private Map<String, Object> purchasePaymentRow(PurchasePayment p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("purchaseId", p.getPurchase() != null ? p.getPurchase().getId() : null);
        row.put("amount", p.getAmount());
        row.put("paymentMethod", name(p.getPaymentMethod()));
        row.put("employeeShiftId", p.getEmployeeShift() != null ? p.getEmployeeShift().getId() : null);
        row.put("drawerMovementId", p.getDrawerMovement() != null ? p.getDrawerMovement().getId() : null);
        row.put("paymentDate", p.getPaymentDate());
        row.put("recordedById", p.getRecordedBy() != null ? p.getRecordedBy().getId() : null);
        row.put("notes", p.getNotes());
        return row;
    }

    private Map<String, Object> cashDrawerSessionRow(CashDrawerSession s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("drawerId", s.getDrawer() != null ? s.getDrawer().getId() : null);
        row.put("openedById", s.getOpenedBy() != null ? s.getOpenedBy().getId() : null);
        row.put("closedById", s.getClosedBy() != null ? s.getClosedBy().getId() : null);
        row.put("openedAt", s.getOpenedAt());
        row.put("closedAt", s.getClosedAt());
        row.put("status", name(s.getStatus()));
        row.put("expectedCash", s.getExpectedCash());
        row.put("openingFloat", s.getOpeningFloat());
        row.put("countedCash", s.getCountedCash());
        row.put("varianceAmount", s.getVarianceAmount());
        row.put("closingNote", s.getClosingNote());
        row.put("blindClose", s.getBlindClose());
        row.put("forceClosed", s.getForceClosed());
        row.put("forceCloseReason", s.getForceCloseReason());
        row.put("createdAt", s.getCreatedAt());
        row.put("updatedAt", s.getUpdatedAt());
        return row;
    }

    private Map<String, Object> cashDrawerMovementRow(CashDrawerMovement m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.getId());
        row.put("drawerSessionId", m.getDrawerSession() != null ? m.getDrawerSession().getId() : null);
        row.put("movementType", name(m.getMovementType()));
        row.put("amount", m.getAmount());
        row.put("referenceType", m.getReferenceType());
        row.put("referenceId", m.getReferenceId());
        row.put("performedById", m.getPerformedBy() != null ? m.getPerformedBy().getId() : null);
        row.put("employeeShiftId", m.getEmployeeShift() != null ? m.getEmployeeShift().getId() : null);
        row.put("reason", m.getReason());
        row.put("occurredAt", m.getOccurredAt());
        return row;
    }

    private Map<String, Object> countSheetRow(CountSheet c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("sheetNumber", c.getSheetNumber());
        row.put("status", name(c.getStatus()));
        row.put("countType", name(c.getCountType()));
        row.put("scheduledDate", c.getScheduledDate());
        row.put("startedAt", c.getStartedAt());
        row.put("submittedAt", c.getSubmittedAt());
        row.put("approvedAt", c.getApprovedAt());
        row.put("createdBy", c.getCreatedBy());
        row.put("submittedBy", c.getSubmittedBy());
        row.put("approvedBy", c.getApprovedBy());
        row.put("notes", c.getNotes());
        row.put("varianceValue", c.getVarianceValue());
        row.put("syncKey", c.getSyncKey());
        row.put("clientSubmittedAt", c.getClientSubmittedAt());
        row.put("syncReceivedAt", c.getSyncReceivedAt());
        row.put("createdAt", c.getCreatedAt());
        row.put("updatedAt", c.getUpdatedAt());
        List<Map<String, Object>> items = new ArrayList<>();
        if (c.getItems() != null) {
            for (CountSheetItem it : c.getItems()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", it.getId());
                item.put("productId", it.getProduct() != null ? it.getProduct().getId() : null);
                item.put("systemQuantity", it.getSystemQuantity());
                item.put("countedQuantity", it.getCountedQuantity());
                item.put("variance", it.getVariance());
                item.put("liveQuantityAtApply", it.getLiveQuantityAtApply());
                item.put("varianceAtApply", it.getVarianceAtApply());
                item.put("clientSystemQuantity", it.getClientSystemQuantity());
                item.put("reasonCode", it.getReasonCode());
                item.put("valuationAmount", it.getValuationAmount());
                item.put("countedAt", it.getCountedAt());
                item.put("note", it.getNote());
                items.add(item);
            }
        }
        row.put("items", items);
        return row;
    }

    private Map<String, Object> glMappingRow(GlMapping m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.getId());
        row.put("eventType", name(m.getEventType()));
        row.put("glAccountId", m.getAccount() != null ? m.getAccount().getId() : null);
        row.put("description", m.getDescription());
        row.put("createdAt", m.getCreatedAt());
        return row;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
