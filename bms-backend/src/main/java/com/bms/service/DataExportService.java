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
            // 🔒 Users are intentionally EXCLUDED (password hashes shouldn't travel).
            //    If you want them, add: data.put("users", userRepository.findAll());

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