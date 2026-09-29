package com.bms.service;

import com.bms.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DataExportService {

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
    // ⚠️ If a repository name is different in your project, adjust it here.

    /**
     * Serializes INSIDE the transaction so lazy collections load correctly.
     */
    @Transactional(readOnly = true)
    public byte[] exportAllAsJson() {
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .findAndRegisterModules()   // handles LocalDateTime
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("categories", categoryRepository.findAll());
            data.put("products", productRepository.findAll());
            data.put("customers", customerRepository.findAll());
            data.put("suppliers", supplierRepository.findAll());
            data.put("sales", saleRepository.findAll());
            data.put("purchases", purchaseRepository.findAll());

            // Sale returns are flattened (sale_id / returned_by / sale_item_id only).
            data.put("saleReturns", saleReturnRepository.findAll().stream()
                    .map(r -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", r.getId());
                        row.put("saleId", r.getSale() != null ? r.getSale().getId() : null);
                        row.put("returnedById", r.getReturnedBy() != null ? r.getReturnedBy().getId() : null);
                        row.put("returnDate", r.getReturnDate());
                        row.put("reason", r.getReason());
                        row.put("totalReturnAmount", r.getTotalReturnAmount());
                        row.put("items", r.getItems() == null ? List.of() : r.getItems().stream()
                                .map(it -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("id", it.getId());
                                    item.put("saleItemId", it.getSaleItem() != null ? it.getSaleItem().getId() : null);
                                    item.put("quantityReturned", it.getQuantityReturned());
                                    item.put("returnAmount", it.getReturnAmount());
                                    return item;
                                })
                                .toList());
                        return row;
                    })
                    .toList());
            data.put("expenses", expenseRepository.findAll());
            data.put("cashShifts", cashShiftRepository.findAll());

            // Users are flattened so the password hash travels with the backup but
            // Jackson never re-serializes the UserDetails/security properties. The
            // role is exported by name (roles table is never wiped/restored).
            data.put("users", userRepository.findAll().stream()
                    .map(u -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", u.getId());
                        row.put("username", u.getUsername());
                        row.put("email", u.getEmail());
                        row.put("password", u.getPassword());
                        row.put("firstName", u.getFirstName());
                        row.put("lastName", u.getLastName());
                        row.put("phone", u.getPhone());
                        row.put("roleName", u.getRole() != null && u.getRole().getName() != null ? u.getRole().getName().name() : null);
                        row.put("isActive", u.getIsActive());
                        row.put("deletedAt", u.getDeletedAt());
                        row.put("preferredLanguage", u.getPreferredLanguage());
                        row.put("createdAt", u.getCreatedAt());
                        row.put("updatedAt", u.getUpdatedAt());
                        return row;
                    })
                    .toList());
            data.put("shopInfo", shopInfoRepository.findAll());
            data.put("systemSettings", systemSettingRepository.findAll());

            // AR payments are flattened (invoice_id / recorded_by_id only) so the
            // associations never pull in nested Sale/User objects.
            data.put("arPayments", arPaymentRepository.findAll().stream()
                    .map(p -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", p.getId());
                        row.put("invoiceId", p.getInvoice() != null ? p.getInvoice().getId() : null);
                        row.put("amount", p.getAmount());
                        row.put("paymentDate", p.getPaymentDate());
                        row.put("recordedById", p.getRecordedBy() != null ? p.getRecordedBy().getId() : null);
                        row.put("notes", p.getNotes());
                        return row;
                    })
                    .toList());
            data.put("receiptCustomizations", receiptCustomizationRepository.findAll());
            // Orders are flattened (customer_id / product_id / cashier_id only) so the
            // associations never pull in nested Customer/Product objects.
            data.put("orders", orderRepository.findAll().stream()
                    .map(o -> {
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
                        row.put("status", o.getStatus().name());
                        row.put("convertedSaleId", o.getConvertedSaleId());
                        row.put("convertedAt", o.getConvertedAt());
                        row.put("cancelledAt", o.getCancelledAt());
                        row.put("cancelledBy", o.getCancelledBy());
                        row.put("cancelReason", o.getCancelReason());
                        row.put("notes", o.getNotes());
                        row.put("isActive", o.getIsActive());
                        row.put("deletedAt", o.getDeletedAt());
                        row.put("updatedAt", o.getUpdatedAt());
                        row.put("items", o.getItems() == null ? List.of() : o.getItems().stream()
                                .map(it -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("id", it.getId());
                                    item.put("productId", it.getProduct() != null ? it.getProduct().getId() : null);
                                    item.put("quantity", it.getQuantity());
                                    item.put("unitPrice", it.getUnitPrice());
                                    item.put("totalPrice", it.getTotalPrice());
                                    item.put("taxAmount", it.getTaxAmount());
                                    item.put("costPriceAtOrder", it.getCostPriceAtOrder());
                                    return item;
                                })
                                .toList());
                        return row;
                    })
                    .toList());
            data.put("orderSequences", orderSequenceRepository.findAll());
            // 🔐 Users ARE exported (flattened, with password hash + role name) so a
            //    restore reproduces logins. Roles themselves are reference data and
            //    are never exported/restored — the role is re-attached by name.

            Map<String, Object> backup = new LinkedHashMap<>();
            backup.put("app", "LumiPOS");
            backup.put("backupVersion", "1.0");
            backup.put("exportedAt", LocalDateTime.now().toString());
            backup.put("data", data);

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(backup);
        } catch (Exception e) {
            throw new RuntimeException("Export failed: " + e.getMessage(), e);
        }
    }
}