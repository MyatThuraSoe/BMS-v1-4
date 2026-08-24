package com.bms.service;

import com.bms.entity.*;
import com.bms.repository.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DataImportService {

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
    private final JdbcTemplate jdbcTemplate;
    private final Environment env;

    @PersistenceContext
    private EntityManager entityManager;

    public enum ImportMode { REPLACE_ALL, MERGE }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> importAll(Map<String, Object> backup, ImportMode mode) {

        Map<String, Object> rawData = (Map<String, Object>) backup.get("data");
        if (rawData == null) throw new IllegalArgumentException("Invalid backup file: missing 'data' section");

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 1️⃣ Parse JSON → Entities
        List<Category>  categories = readList(mapper, rawData.get("categories"), Category.class);
        List<Product>   products   = readList(mapper, rawData.get("products"),   Product.class);
        List<Customer>  customers  = readList(mapper, rawData.get("customers"),  Customer.class);
        List<Supplier>  suppliers  = readList(mapper, rawData.get("suppliers"),  Supplier.class);
        List<Sale>      sales      = readList(mapper, rawData.get("sales"),      Sale.class);
        List<Purchase>  purchases  = readList(mapper, rawData.get("purchases"),  Purchase.class);
        List<ReceiptCustomization> receiptCustomizations =
                readList(mapper, rawData.get("receiptCustomizations"), ReceiptCustomization.class);

        // AR payments are stored flattened (invoiceId / recordedById only) in the
        // backup — re-attach the associations by id after deserialization.
        List<ArPayment> arPayments = readArPayments(mapper, rawData.get("arPayments"));

        // Orders are stored flattened (customerId / productId only) — re-attach the
        // associations by id after deserialization.
        List<com.bms.entity.Order> orders = readOrders(mapper, rawData.get("orders"));
        List<OrderSequence> orderSequences = readList(mapper, rawData.get("orderSequences"), OrderSequence.class);

        // 2️⃣ REPLACE mode: wipe existing data with FK checks disabled so child
        //    tables (stock_movements, refunds, *_items, product_images, ...) never
        //    block the delete or get orphaned.
        if (mode == ImportMode.REPLACE_ALL) {
            wipeAllData();
        }

        // 3️⃣ Re-link children (back-refs were stripped by @JsonIgnore)
        sales.forEach(s -> { if (s.getItems() != null) s.getItems().forEach(i -> i.setSale(s)); });
        purchases.forEach(p -> { if (p.getItems() != null) p.getItems().forEach(i -> i.setPurchase(p)); });

        // 4️⃣ Save (parents first). IDs are preserved →
        //    MERGE = existing IDs updated, new IDs inserted.
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("categories", categoryRepository.saveAll(categories).size());
        counts.put("customers",  customerRepository.saveAll(customers).size());
        counts.put("suppliers",  supplierRepository.saveAll(suppliers).size());
        counts.put("products",   productRepository.saveAll(products).size());
        counts.put("sales",      saleRepository.saveAll(sales).size());
        counts.put("purchases",  purchaseRepository.saveAll(purchases).size());
        counts.put("receiptCustomizations", receiptCustomizationRepository.saveAll(receiptCustomizations).size());
        counts.put("arPayments", arPaymentRepository.saveAll(arPayments).size());
        counts.put("orders", orderRepository.saveAll(orders).size());
        counts.put("orderSequences", orderSequenceRepository.saveAll(orderSequences).size());

        // 5️⃣ Reset auto-increment counters so NEW records don't collide
        resetIdentityCounters();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mode", mode.name());
        result.put("counts", counts);
        return result;
    }

    private void wipeAllData() {
        String url = env.getProperty("spring.datasource.url", "");
        boolean isH2 = url.contains(":h2:");
        String disableFk = isH2
                ? "SET REFERENTIAL_INTEGRITY FALSE"
                : "SET FOREIGN_KEY_CHECKS = 0";
        String enableFk = isH2
                ? "SET REFERENTIAL_INTEGRITY TRUE"
                : "SET FOREIGN_KEY_CHECKS = 1";

        String[] tables = {
            "ar_payments", "receipt_customizations",
            "sale_return_items", "sale_returns", "refund_items", "refunds",
            "sale_items", "sales",
            "purchase_items", "purchases",
            "order_items", "orders", "order_sequences",
            "stock_movements",
            "product_price_history", "product_images",
            "products", "categories", "customers", "suppliers"
        };

        jdbcTemplate.execute(disableFk);
        try {
            for (String table : tables) {
                try {
                    jdbcTemplate.execute("DELETE FROM " + table);
                } catch (Exception ignored) {
                    // Table may not exist in an older installation — safe to skip
                }
            }
        } finally {
            jdbcTemplate.execute(enableFk);
        }
        // Drop any JPA entities currently cached; SQL deletes bypass the
        // persistence context and stale state would corrupt the upcoming saveAll.
        entityManager.clear();
    }

    private <T> List<T> readList(ObjectMapper mapper, Object raw, Class<T> type) {
        if (raw == null) return List.of();
        return mapper.convertValue(raw,
                mapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    /** Rebuilds ArPayment rows from the flattened backup format and re-attaches
     *  the Sale / User associations by id (proxies, not full loads). */
    @SuppressWarnings("unchecked")
    private List<ArPayment> readArPayments(ObjectMapper mapper, Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<ArPayment> result = new ArrayList<>();
        for (Object item : list) {
            ArPayment payment = mapper.convertValue(item, ArPayment.class);
            Map<String, Object> row = (Map<String, Object>) item;
            Long invoiceId = numericOrNull(row.get("invoiceId"));
            Long recordedById = numericOrNull(row.get("recordedById"));
            if (invoiceId != null) {
                payment.setInvoice(entityManager.getReference(Sale.class, invoiceId));
            }
            if (recordedById != null) {
                payment.setRecordedBy(entityManager.getReference(User.class, recordedById));
            }
            result.add(payment);
        }
        return result;
    }

    /** Rebuilds Order rows from the flattened backup format and re-attaches the
     *  Customer / Product associations by id, and re-parents OrderItems. */
    @SuppressWarnings("unchecked")
    private List<com.bms.entity.Order> readOrders(ObjectMapper mapper, Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<com.bms.entity.Order> result = new ArrayList<>();
        for (Object item : list) {
            com.bms.entity.Order order = mapper.convertValue(item, com.bms.entity.Order.class);
            Map<String, Object> row = (Map<String, Object>) item;
            Long customerId = numericOrNull(row.get("customerId"));
            if (customerId != null) {
                order.setCustomer(entityManager.getReference(Customer.class, customerId));
            }
            if (order.getStatus() == null && row.get("status") != null) {
                order.setStatus(com.bms.entity.Order.OrderStatus.valueOf(row.get("status").toString()));
            }
            order.getItems().clear();
            Object rawItems = row.get("items");
            if (rawItems instanceof List<?> itemRows) {
                for (Object itemRowRaw : itemRows) {
                    Map<String, Object> ir = (Map<String, Object>) itemRowRaw;
                    OrderItem oi = mapper.convertValue(itemRowRaw, OrderItem.class);
                    Long productId = numericOrNull(ir.get("productId"));
                    if (productId != null) {
                        oi.setProduct(entityManager.getReference(Product.class, productId));
                    }
                    oi.setOrder(order);
                    order.getItems().add(oi);
                }
            }
            result.add(order);
        }
        return result;
    }

    private Long numericOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    private void resetIdentityCounters() {
        String url = env.getProperty("spring.datasource.url", "");
        boolean isH2 = url.contains(":h2:");
        String[] tables = {"categories", "customers", "suppliers", "products",
                "sales", "sale_items", "purchases", "purchase_items", "ar_payments", "receipt_customizations"};
        for (String table : tables) {
            try {
                Long max = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
                if (max == null || max == 0) continue;
                String sql = isH2
                        ? "ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + (max + 1)
                        : "ALTER TABLE " + table + " AUTO_INCREMENT = " + (max + 1);
                jdbcTemplate.execute(sql);
            } catch (Exception ignored) {
                // Table name may differ in your schema — safe to skip
            }
        }
    }
}