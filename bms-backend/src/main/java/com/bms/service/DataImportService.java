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
    private final SaleReturnRepository saleReturnRepository;
    private final ExpenseRepository expenseRepository;
    private final CashShiftRepository cashShiftRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ShopInfoRepository shopInfoRepository;
    private final SystemSettingRepository systemSettingRepository;
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

        // Sale returns are stored flattened (saleId / returnedById / saleItemId) —
        // re-attach the associations by id and re-parent the items.
        List<SaleReturn> saleReturns = readSaleReturns(mapper, rawData.get("saleReturns"));

        // Users are stored flattened (roleName only) — re-attach the Role entity.
        List<User> users = readUsers(mapper, rawData.get("users"));

        List<Expense> expenses = readList(mapper, rawData.get("expenses"), Expense.class);
        List<CashShift> cashShifts = readList(mapper, rawData.get("cashShifts"), CashShift.class);
        List<ShopInfo> shopInfoRows = readList(mapper, rawData.get("shopInfo"), ShopInfo.class);
        List<SystemSetting> systemSettings = readList(mapper, rawData.get("systemSettings"), SystemSetting.class);

        // 2️⃣ REPLACE mode: wipe existing data with FK checks disabled so child
        //    tables (stock_movements, refunds, *_items, product_images, ...) never
        //    block the delete or get orphaned. Tables added in newer backup
        //    versions (users, expenses, ...) are only wiped when the incoming
        //    backup actually contains their data — a restore from an old file can
        //    never lock you out by replacing everything else with nothing.
        if (mode == ImportMode.REPLACE_ALL) {
            wipeAllData(rawData);
        }

        // 3️⃣ Re-link children (back-refs were stripped by @JsonIgnore)
        sales.forEach(s -> { if (s.getItems() != null) s.getItems().forEach(i -> i.setSale(s)); });
        purchases.forEach(p -> { if (p.getItems() != null) p.getItems().forEach(i -> i.setPurchase(p)); });

        // 4️⃣ MERGE = insert-only for records we do NOT already have. Existing IDs
        //    are left untouched — nothing in the store is ever silently overwritten
        //    by a "safe" merge.
        if (mode == ImportMode.MERGE) {
            users = keepOnlyNewUsers(users);
            categories = keepOnlyNew(categories, categoryRepository::findAll);
            customers = keepOnlyNew(customers, customerRepository::findAll);
            suppliers = keepOnlyNew(suppliers, supplierRepository::findAll);
            products = keepOnlyNew(products, productRepository::findAll);
            purchases = keepOnlyNew(purchases, purchaseRepository::findAll);
            sales = keepOnlyNew(sales, saleRepository::findAll);
            receiptCustomizations = keepOnlyNew(receiptCustomizations, receiptCustomizationRepository::findAll);
            arPayments = keepOnlyNew(arPayments, arPaymentRepository::findAll);
            orders = keepOnlyNew(orders, orderRepository::findAll);
            orderSequences = keepOnlyNew(orderSequences, orderSequenceRepository::findAll);
            saleReturns = keepOnlyNew(saleReturns, saleReturnRepository::findAll);
            expenses = keepOnlyNew(expenses, expenseRepository::findAll);
            cashShifts = keepOnlyNew(cashShifts, cashShiftRepository::findAll);
            shopInfoRows = keepOnlyNew(shopInfoRows, shopInfoRepository::findAll);
            systemSettings = keepOnlyNew(systemSettings, systemSettingRepository::findAll);
        }

        // 5️⃣ Save (parents first, FK-safe order). IDs are preserved so
        //    REPLACE_ALL re-inserts the exact rows and MERGE inserts new ones.
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("users", userRepository.saveAll(users).size());
        counts.put("categories", categoryRepository.saveAll(categories).size());
        counts.put("customers",  customerRepository.saveAll(customers).size());
        counts.put("suppliers",  supplierRepository.saveAll(suppliers).size());
        counts.put("products",   productRepository.saveAll(products).size());
        counts.put("purchases",  purchaseRepository.saveAll(purchases).size());
        counts.put("sales",      saleRepository.saveAll(sales).size());
        counts.put("saleReturns", saleReturnRepository.saveAll(saleReturns).size());
        counts.put("receiptCustomizations", receiptCustomizationRepository.saveAll(receiptCustomizations).size());
        counts.put("arPayments", arPaymentRepository.saveAll(arPayments).size());
        counts.put("orders", orderRepository.saveAll(orders).size());
        counts.put("orderSequences", orderSequenceRepository.saveAll(orderSequences).size());
        counts.put("cashShifts", cashShiftRepository.saveAll(cashShifts).size());
        counts.put("expenses", expenseRepository.saveAll(expenses).size());
        counts.put("shopInfo", shopInfoRepository.saveAll(shopInfoRows).size());
        counts.put("systemSettings", systemSettingRepository.saveAll(systemSettings).size());

        // 6️⃣ Reset auto-increment counters so NEW records don't collide
        resetIdentityCounters();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mode", mode.name());
        result.put("counts", counts);
        return result;
    }

    /** In MERGE mode, drops records whose ID already exists in the destination. */
    private <T> List<T> keepOnlyNew(List<T> incoming, java.util.function.Supplier<List<T>> existingLoader) {
        if (incoming == null || incoming.isEmpty()) return List.of();
        java.util.Set<Object> existingIds = existingLoader.get().stream()
                .map(e -> {
                    try {
                        java.lang.reflect.Method m = e.getClass().getMethod("getId");
                        return m.invoke(e);
                    } catch (Exception ex) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return incoming.stream()
                .filter(e -> {
                    try {
                        java.lang.reflect.Method m = e.getClass().getMethod("getId");
                        Object id = m.invoke(e);
                        return id == null || !existingIds.contains(id);
                    } catch (Exception ex) {
                        return true;
                    }
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /** Users must never collide on username/email (unique columns), not just ID. */
    private List<User> keepOnlyNewUsers(List<User> incoming) {
        if (incoming == null || incoming.isEmpty()) return List.of();
        java.util.Set<String> usernames = incoming.stream()
                .map(User::getUsername).filter(java.util.Objects::nonNull).map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> emails = incoming.stream()
                .map(User::getEmail).filter(java.util.Objects::nonNull).map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> existingUsernames = userRepository.findAll().stream()
                .map(User::getUsername).filter(java.util.Objects::nonNull).map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> existingEmails = userRepository.findAll().stream()
                .map(User::getEmail).filter(java.util.Objects::nonNull).map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
        usernames.removeAll(existingUsernames);
        emails.removeAll(existingEmails);
        return incoming.stream()
                .filter(u -> u.getUsername() == null || usernames.contains(u.getUsername().toLowerCase()))
                .filter(u -> u.getEmail() == null || emails.contains(u.getEmail().toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
    }

    private void wipeAllData(Map<String, Object> rawData) {
        String url = env.getProperty("spring.datasource.url", "");
        boolean isH2 = url.contains(":h2:");
        boolean isSqlite = url.contains(":sqlite:");
        String disableFk;
        String enableFk;
        if (isH2) {
            disableFk = "SET REFERENTIAL_INTEGRITY FALSE";
            enableFk = "SET REFERENTIAL_INTEGRITY TRUE";
        } else if (isSqlite) {
            disableFk = "PRAGMA foreign_keys = OFF";
            enableFk = "PRAGMA foreign_keys = ON";
        } else {
            disableFk = "SET FOREIGN_KEY_CHECKS = 0";
            enableFk = "SET FOREIGN_KEY_CHECKS = 1";
        }

        List<String> tables = new ArrayList<>(List.of(
            "ar_payments", "receipt_customizations",
            "sale_return_items", "sale_returns", "refund_items", "refunds",
            "sale_items", "sales",
            "purchase_items", "purchases",
            "order_items", "orders", "order_sequences",
            "stock_movements",
            "product_price_history", "product_images",
            "products", "categories", "customers", "suppliers",
            "customer_phones", "supplier_phones"
        ));

        // Tables introduced in newer backup versions are only wiped when the
        // incoming backup actually contains their data. This keeps old backups
        // usable (no wiping users "away" just because the old file lacks them)
        // while still making new backups restore completely.
        wipeIfPresent(tables, "users", rawData.get("users"));
        wipeIfPresent(tables, "expenses", rawData.get("expenses"));
        wipeIfPresent(tables, "cash_shifts", rawData.get("cashShifts"));
        // shop_info / system_settings carry the app identity (name, currency,
        // printer/time settings): always restore them when the backup provides
        // them, but never delete them when the backup doesn't.
        wipeIfPresent(tables, "shop_info", rawData.get("shopInfo"));
        wipeIfPresent(tables, "system_settings", rawData.get("systemSettings"));

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

    private void wipeIfPresent(List<String> tables, String table, Object raw) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            tables.add(table);
        }
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

    /** Rebuilds SaleReturn rows from the flattened backup format and re-attaches
     *  the Sale / User / SaleItem associations by id, and re-parents the items. */
    @SuppressWarnings("unchecked")
    private List<SaleReturn> readSaleReturns(ObjectMapper mapper, Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<SaleReturn> result = new ArrayList<>();
        for (Object item : list) {
            SaleReturn saleReturn = mapper.convertValue(item, SaleReturn.class);
            Map<String, Object> row = (Map<String, Object>) item;
            Long saleId = numericOrNull(row.get("saleId"));
            Long returnedById = numericOrNull(row.get("returnedById"));
            if (saleId != null) {
                saleReturn.setSale(entityManager.getReference(Sale.class, saleId));
            }
            if (returnedById != null) {
                saleReturn.setReturnedBy(entityManager.getReference(User.class, returnedById));
            }
            saleReturn.getItems().clear();
            Object rawItems = row.get("items");
            if (rawItems instanceof List<?> itemRows) {
                for (Object itemRowRaw : itemRows) {
                    Map<String, Object> ir = (Map<String, Object>) itemRowRaw;
                    SaleReturnItem sri = mapper.convertValue(itemRowRaw, SaleReturnItem.class);
                    Long saleItemId = numericOrNull(ir.get("saleItemId"));
                    if (saleItemId != null) {
                        sri.setSaleItem(entityManager.getReference(SaleItem.class, saleItemId));
                    }
                    sri.setSaleReturn(saleReturn);
                    saleReturn.getItems().add(sri);
                }
            }
            result.add(saleReturn);
        }
        return result;
    }

    /** Rebuilds User rows from the flattened backup format and re-attaches the
     *  Role entity by its name (roles are reference data, never restored). */
    @SuppressWarnings("unchecked")
    private List<User> readUsers(ObjectMapper mapper, Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<User> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> row = (Map<String, Object>) item;
            Map<String, Object> clean = new LinkedHashMap<>(row);
            clean.remove("roleName");
            User user = mapper.convertValue(clean, User.class);
            Object roleName = row.get("roleName");
            if (roleName != null) {
                try {
                    roleRepository.findByName(Role.RoleName.valueOf(roleName.toString()))
                            .ifPresent(user::setRole);
                } catch (IllegalArgumentException ignored) {
                    // Unknown role name in backup — role stays null (login still blocked).
                }
            }
            result.add(user);
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
        boolean isSqlite = url.contains(":sqlite:");
        String[] tables = {"users", "categories", "customers", "suppliers", "products",
                "sales", "sale_items", "purchases", "purchase_items", "ar_payments", "receipt_customizations",
                "orders", "order_items", "order_sequences", "sale_returns", "sale_return_items",
                "expenses", "cash_shifts", "shop_info", "system_settings"};
        for (String table : tables) {
            try {
                if (isSqlite) {
                    // SQLite INTEGER PRIMARY KEY is a rowid alias — inserts after a
                    // restored explicit max id automatically continue from there.
                    continue;
                }
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