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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DataImportService {

    @Autowired
    private ShopTimeService shopTimeService;

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

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PeriodGuard periodGuard;

    @Autowired
    private PeriodCloseRepository periodCloseRepository;

    @Autowired
    private InventoryLedgerRepository inventoryLedgerRepository;

    @Autowired
    private ProfitLedgerRepository profitLedgerRepository;

    @Autowired
    private LedgerReadService ledgerReadService;

    @Autowired
    private InstallIdentity installIdentity;

    public enum ImportMode { REPLACE_ALL, MERGE }

    /**
     * What the shop is told when a file cannot be merged on the numbers it
     * carries. It is a choice the shop has to make, not something to guess at:
     * only the person who owns both tills knows whether the file is this shop's
     * own older export or somebody else's.
     */
    public static final String OTHER_SHOP_MESSAGE =
            "This file came from another shop or an older export. Use Replace everything, "
                    + "or confirm that it is the same shop.";

    @Transactional
    public Map<String, Object> importAll(Map<String, Object> backup, ImportMode mode) {
        return importAll(backup, mode, false);
    }

    /**
     * @param confirmSameShop the shop's own answer to the question the file could
     *                        not answer: it does not say this install's id, so a
     *                        person has to say whether it belongs here. It buys
     *                        the right to try the merge, never the right to mix
     *                        two sets of books — the rows themselves are matched
     *                        against each other first.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> importAll(Map<String, Object> backup, ImportMode mode,
                                         boolean confirmSameShop) {
        periodGuard.lock();

        Map<String, Object> rawData = (Map<String, Object>) backup.get("data");
        if (rawData == null) throw new IllegalArgumentException("Invalid backup file: missing 'data' section");

        // A file says which install it came out of, and that answer decides how
        // much of its numbering can be trusted. Everything written here agrees
        // with itself; anything else is a stranger wearing this shop's id numbers.
        String fileId = installIdOf(backup);
        boolean fromThisInstall = installIdentity.currentId().equals(fileId);
        if (mode == ImportMode.MERGE && !fromThisInstall && !confirmSameShop) {
            throw new com.bms.exception.StateConflictException(OTHER_SHOP_MESSAGE);
        }

        // A JSON file that carries both journals IS a complete backup, so it may
        // replace the closed periods and the ledgers with itself. A business-only
        // file never may: swapping rows underneath a closed period would leave the
        // books disagreeing with the shop's history.
        boolean carriesLedgers = hasRows(rawData, "inventoryLedger") && hasRows(rawData, "profitLedger");

        if (mode == ImportMode.REPLACE_ALL && !carriesLedgers && (periodCloseRepository.count() > 0
                || inventoryLedgerRepository.count() > 0 || profitLedgerRepository.count() > 0)) {
            throw new com.bms.exception.BusinessException(
                    "Cannot replace business rows independently of accounting ledgers. Use a complete database restore instead.");
        }

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

        // AR payments are stored flattened (invoice / recorder / shift / drawer
        // movement by id) in the backup — re-attach the associations after reading.
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
        List<Long> internalSettingIds = systemSettingRepository.findAll().stream()
                .filter(setting -> setting.getSettingKey().startsWith("accounting."))
                .map(SystemSetting::getId).toList();
        List<SystemSetting> systemSettings = readList(mapper, rawData.get("systemSettings"), SystemSetting.class).stream()
                .filter(setting -> !setting.getSettingKey().startsWith("accounting."))
                // Each install has its own id, and the one in the file is already
                // read from the top of the file. As a setting row it would let a
                // merge hand this shop another shop's name.
                .filter(setting -> !InstallIdentity.SETTING_KEY.equals(setting.getSettingKey()))
                // A file written before the token stopped being exported carries
                // the print agent token of the shop that wrote it. Importing it
                // would point this install's queue at that other shop's agent.
                .filter(setting -> !SystemSettingService.SECRET_KEYS.contains(setting.getSettingKey()))
                .toList();
        if (systemSettings.stream().anyMatch(setting -> internalSettingIds.contains(setting.getId()))) {
            throw new com.bms.exception.BusinessException("Backup setting IDs conflict with internal accounting settings");
        }

        // 1b Sections added by later backup versions. Every one of them is
        //     optional: a file written by an older version simply has no key for
        //     it, and restoring that file must not empty tables it never described.
        List<InvoiceSequence> invoiceSequences = readList(mapper, rawData.get("invoiceSequences"), InvoiceSequence.class);
        List<PurchaseSequence> purchaseSequences = readList(mapper, rawData.get("purchaseSequences"), PurchaseSequence.class);
        List<CustomerSequence> customerSequences = readList(mapper, rawData.get("customerSequences"), CustomerSequence.class);
        List<TransferSequence> transferSequences = readList(mapper, rawData.get("transferSequences"), TransferSequence.class);
        List<Location> locations = readList(mapper, rawData.get("locations"), Location.class);
        List<GlAccount> glAccounts = readList(mapper, rawData.get("glAccounts"), GlAccount.class);
        List<CashDrawer> cashDrawers = readList(mapper, rawData.get("cashDrawers"), CashDrawer.class);
        List<PeriodClose> periodCloses = readList(mapper, rawData.get("periodCloses"), PeriodClose.class);
        List<InventoryLedger> inventoryLedger = readList(mapper, rawData.get("inventoryLedger"), InventoryLedger.class);
        List<ProfitLedger> profitLedger = readList(mapper, rawData.get("profitLedger"), ProfitLedger.class);

        List<GlMapping> glMappings = readFlat(mapper, rawData.get("glMappings"), GlMapping.class,
                (mapping, row) -> mapping.setAccount(reference(row.get("glAccountId"), GlAccount.class)));

        List<LocationStock> locationStock = readFlat(mapper, rawData.get("locationStock"), LocationStock.class,
                (stock, row) -> {
                    stock.setLocation(reference(row.get("locationId"), Location.class));
                    stock.setProduct(reference(row.get("productId"), Product.class));
                });

        List<StockMovement> stockMovements = readFlat(mapper, rawData.get("stockMovements"), StockMovement.class,
                (movement, row) -> {
                    movement.setProduct(reference(row.get("productId"), Product.class));
                    movement.setCreatedBy(reference(row.get("createdById"), User.class));
                });

        List<StockTransfer> stockTransfers = readFlat(mapper, rawData.get("stockTransfers"), StockTransfer.class,
                (transfer, row) -> {
                    transfer.setFromLocation(reference(row.get("fromLocationId"), Location.class));
                    transfer.setToLocation(reference(row.get("toLocationId"), Location.class));
                    transfer.getItems().clear();
                    for (Object itemRow : maps(row.get("items"))) {
                        Map<String, Object> ir = mapOf(itemRow);
                        StockTransferItem item = mapper.convertValue(itemRow, StockTransferItem.class);
                        item.setProduct(reference(ir.get("productId"), Product.class));
                        item.setTransfer(transfer);
                        transfer.getItems().add(item);
                    }
                });

        List<PriceList> priceLists = readFlat(mapper, rawData.get("priceLists"), PriceList.class,
                (priceList, row) -> {
                    priceList.getItems().clear();
                    for (Object itemRow : maps(row.get("items"))) {
                        Map<String, Object> ir = mapOf(itemRow);
                        PriceListItem item = mapper.convertValue(itemRow, PriceListItem.class);
                        item.setProduct(reference(ir.get("productId"), Product.class));
                        item.setPriceList(priceList);
                        priceList.getItems().add(item);
                    }
                });

        List<Promotion> promotions = readFlat(mapper, rawData.get("promotions"), Promotion.class,
                (promotion, row) -> {
                    promotion.setProduct(reference(row.get("productId"), Product.class));
                    promotion.setCategory(reference(row.get("categoryId"), Category.class));
                    promotion.getTiers().clear();
                    for (Object tierRow : maps(row.get("tiers"))) {
                        PromotionTier tier = mapper.convertValue(tierRow, PromotionTier.class);
                        tier.setPromotion(promotion);
                        promotion.getTiers().add(tier);
                    }
                });

        List<ProductPriceHistory> priceHistory = readFlat(mapper, rawData.get("productPriceHistory"),
                ProductPriceHistory.class, (entry, row) -> {
                    entry.setProduct(reference(row.get("productId"), Product.class));
                    entry.setChangedBy(reference(row.get("changedById"), User.class));
                });

        List<PurchasePayment> purchasePayments = readFlat(mapper, rawData.get("purchasePayments"),
                PurchasePayment.class, (payment, row) -> {
                    payment.setPurchase(reference(row.get("purchaseId"), Purchase.class));
                    payment.setEmployeeShift(reference(row.get("employeeShiftId"), CashShift.class));
                    payment.setDrawerMovement(reference(row.get("drawerMovementId"), CashDrawerMovement.class));
                    payment.setRecordedBy(reference(row.get("recordedById"), User.class));
                });

        List<CashDrawerSession> drawerSessions = readFlat(mapper, rawData.get("cashDrawerSessions"),
                CashDrawerSession.class, (session, row) -> {
                    session.setDrawer(reference(row.get("drawerId"), CashDrawer.class));
                    session.setOpenedBy(reference(row.get("openedById"), User.class));
                    session.setClosedBy(reference(row.get("closedById"), User.class));
                });

        List<CashDrawerMovement> drawerMovements = readFlat(mapper, rawData.get("cashDrawerMovements"),
                CashDrawerMovement.class, (movement, row) -> {
                    movement.setDrawerSession(reference(row.get("drawerSessionId"), CashDrawerSession.class));
                    movement.setPerformedBy(reference(row.get("performedById"), User.class));
                    movement.setEmployeeShift(reference(row.get("employeeShiftId"), CashShift.class));
                });

        List<CountSheet> countSheets = readFlat(mapper, rawData.get("countSheets"), CountSheet.class,
                (sheet, row) -> {
                    sheet.getItems().clear();
                    for (Object itemRow : maps(row.get("items"))) {
                        Map<String, Object> ir = mapOf(itemRow);
                        CountSheetItem item = mapper.convertValue(itemRow, CountSheetItem.class);
                        item.setProduct(reference(ir.get("productId"), Product.class));
                        item.setCountSheet(sheet);
                        sheet.getItems().add(item);
                    }
                });

        // 2️⃣ REPLACE mode: wipe existing data with FK checks disabled so child
        //    tables (stock_movements, refunds, *_items, product_images, ...) never
        //    block the delete or get orphaned. Tables added in newer backup
        //    versions (users, expenses, ...) are only wiped when the incoming
        //    backup actually contains their data — a restore from an old file can
        //    never lock you out by replacing everything else with nothing.
        if (mode == ImportMode.REPLACE_ALL) {
            // A file that carries its own journals also carries the closes that
            // froze them: its history is exactly the history those closes
            // describe, so the period checks are its own business.
            if (!carriesLedgers) {
                for (Sale sale : sales) periodGuard.assertOpen(sale.getSaleDate());
                for (SaleReturn saleReturn : saleReturns) periodGuard.assertOpen(saleReturn.getReturnDate());
                for (Purchase purchase : purchases) periodGuard.assertOpen(purchase.getPurchaseDate());
                for (Expense expense : expenses) periodGuard.assertOpen(expense.getExpenseDate());
            }
            wipeAllData(rawData);
        }

        // 3️⃣ Re-link children (back-refs were stripped by @JsonIgnore)
        sales.forEach(s -> {
            if (s.getItems() != null) s.getItems().forEach(i -> i.setSale(s));
            if (s.getPayments() != null) s.getPayments().forEach(p -> p.setSale(s));
        });
        purchases.forEach(p -> { if (p.getItems() != null) p.getItems().forEach(i -> i.setPurchase(p)); });

        // 4️⃣ MERGE = insert-only for records we do NOT already have. Existing IDs
        //    are left untouched — nothing in the store is ever silently overwritten
        //    by a "safe" merge. A file that is not this install's own is held to
        //    more than that: its rows have to say they are the same rows.
        if (mode == ImportMode.MERGE) {
            MergeGate gate = new MergeGate(!fromThisInstall);
            users = keepOnlyNewUsers(users);
            categories = gate.keepOnlyNew("categories", categories, categoryRepository::findAll, null);
            customers = gate.keepOnlyNew("customers", customers, customerRepository::findAll,
                    Customer::getCustomerCode);
            suppliers = gate.keepOnlyNew("suppliers", suppliers, supplierRepository::findAll, null);
            products = gate.keepOnlyNew("products", products, productRepository::findAll, Product::getSku);
            purchases = gate.keepOnlyNew("purchases", purchases, purchaseRepository::findAll,
                    Purchase::getPurchaseNumber);
            sales = gate.keepOnlyNew("sales", sales, saleRepository::findAll, Sale::getInvoiceNumber);
            receiptCustomizations = gate.keepOnlyNew("receiptCustomizations", receiptCustomizations,
                    receiptCustomizationRepository::findAll, null);
            arPayments = gate.keepOnlyNew("arPayments", arPayments, arPaymentRepository::findAll, null);
            orders = gate.keepOnlyNew("orders", orders, orderRepository::findAll, null);
            orderSequences = gate.keepOnlyNew("orderSequences", orderSequences,
                    orderSequenceRepository::findAll, null);
            saleReturns = gate.keepOnlyNew("saleReturns", saleReturns, saleReturnRepository::findAll, null);
            expenses = gate.keepOnlyNew("expenses", expenses, expenseRepository::findAll, null);
            cashShifts = gate.keepOnlyNew("cashShifts", cashShifts, cashShiftRepository::findAll, null);
            shopInfoRows = gate.keepOnlyNew("shopInfo", shopInfoRows, shopInfoRepository::findAll, null);
            systemSettings = gate.keepOnlyNew("systemSettings", systemSettings,
                    systemSettingRepository::findAll, null);
            locations = gate.keepOnlyNew("locations", locations, locationRepository::findAll, null);
            locationStock = gate.keepOnlyNew("locationStock", locationStock,
                    locationStockRepository::findAll, null);
            glAccounts = gate.keepOnlyNew("glAccounts", glAccounts, glAccountRepository::findAll, null);
            glMappings = gate.keepOnlyNew("glMappings", glMappings, glMappingRepository::findAll, null);
            cashDrawers = gate.keepOnlyNew("cashDrawers", cashDrawers, cashDrawerRepository::findAll, null);
            drawerSessions = gate.keepOnlyNew("cashDrawerSessions", drawerSessions,
                    cashDrawerSessionRepository::findAll, null);
            drawerMovements = gate.keepOnlyNew("cashDrawerMovements", drawerMovements,
                    cashDrawerMovementRepository::findAll, null);
            purchasePayments = gate.keepOnlyNew("purchasePayments", purchasePayments,
                    purchasePaymentRepository::findAll, null);
            stockMovements = gate.keepOnlyNew("stockMovements", stockMovements,
                    stockMovementRepository::findAll, null);
            stockTransfers = gate.keepOnlyNew("stockTransfers", stockTransfers,
                    stockTransferRepository::findAll, null);
            priceLists = gate.keepOnlyNew("priceLists", priceLists, priceListRepository::findAll, null);
            promotions = gate.keepOnlyNew("promotions", promotions, promotionRepository::findAll, null);
            priceHistory = gate.keepOnlyNew("productPriceHistory", priceHistory,
                    productPriceHistoryRepository::findAll, null);
            countSheets = gate.keepOnlyNew("countSheets", countSheets, countSheetRepository::findAll, null);
            invoiceSequences = gate.keepOnlyNew("invoiceSequences", invoiceSequences,
                    invoiceSequenceRepository::findAll, null);
            purchaseSequences = gate.keepOnlyNew("purchaseSequences", purchaseSequences,
                    purchaseSequenceRepository::findAll, null);
            customerSequences = gate.keepOnlyNew("customerSequences", customerSequences,
                    customerSequenceRepository::findAll, null);
            transferSequences = gate.keepOnlyNew("transferSequences", transferSequences,
                    transferSequenceRepository::findAll, null);
            // period_close is keyed by the period itself, not an id column.
            java.util.Set<String> alreadyClosed = periodCloseRepository.findAll().stream()
                    .map(PeriodClose::getPeriod).collect(java.util.stream.Collectors.toSet());
            periodCloses = periodCloses.stream()
                    .filter(c -> c.getPeriod() == null || !alreadyClosed.contains(c.getPeriod()))
                    .toList();
            inventoryLedger = gate.keepOnlyNew("inventoryLedger", inventoryLedger,
                    inventoryLedgerRepository::findAll, null);
            profitLedger = gate.keepOnlyNew("profitLedger", profitLedger,
                    profitLedgerRepository::findAll, null);
            gate.refuseRowsThatDoNotMatch();
        }

        if (!carriesLedgers && ledgerReadService.isActive() && (!products.isEmpty() || !sales.isEmpty()
                || !saleReturns.isEmpty() || !purchases.isEmpty() || !expenses.isEmpty()
                || !arPayments.isEmpty() || !customers.isEmpty())) {
            throw new com.bms.exception.BusinessException(
                    "Business-only imports cannot append reconciled accounting history. Use a complete database restore instead.");
        }

        // A backup that carries its own journals also carries the closes that
        // froze them, so the period checks are its own business: the restored
        // history is exactly the history those closes describe.
        if (!carriesLedgers) {
            for (Sale sale : sales) {
                periodGuard.assertOpen(sale.getSaleDate());
                if (sale.getVoidedAt() != null) periodGuard.assertOpen(sale.getVoidedAt());
            }
            for (SaleReturn saleReturn : saleReturns) periodGuard.assertOpen(saleReturn.getReturnDate());
            for (Purchase purchase : purchases) periodGuard.assertOpen(purchase.getPurchaseDate());
            for (Expense expense : expenses) periodGuard.assertOpen(expense.getExpenseDate());
        }

        // 5️⃣ Save (FK-safe order, parents first). Every row is an INSERT: REPLACE
        //    mode has just emptied these tables and MERGE mode dropped the rows the
        //    shop already has, so the ids the file carries name nothing to update.
        //    Places, drawers, shifts and price lists come before the transactions
        //    that point into them: with generated ids every row reaches the database
        //    the moment it is saved, so a link to a row not inserted yet is refused.
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("users", insertAll(users));
        counts.put("categories", insertAll(categories));
        counts.put("customers",  insertAll(customers));
        counts.put("suppliers",  insertAll(suppliers));
        counts.put("glAccounts", insertAll(glAccounts));
        counts.put("locations", insertAll(locations));
        counts.put("cashDrawers", insertAll(cashDrawers));
        counts.put("cashDrawerSessions", insertAll(drawerSessions));
        counts.put("cashShifts", insertAll(cashShifts));
        counts.put("cashDrawerMovements", insertAll(drawerMovements));
        counts.put("products",   insertAll(products));
        counts.put("priceLists", insertAll(priceLists));
        counts.put("locationStock", insertAll(locationStock));
        counts.put("purchases",  insertAll(purchases));
        counts.put("sales",      insertAll(sales));
        counts.put("saleReturns", insertAll(saleReturns));
        counts.put("receiptCustomizations", insertAll(receiptCustomizations));
        counts.put("arPayments", insertAll(arPayments));
        counts.put("purchasePayments", insertAll(purchasePayments));
        counts.put("orders", insertAll(orders));
        counts.put("orderSequences", insertAll(orderSequences));
        counts.put("expenses", insertAll(expenses));
        counts.put("shopInfo", insertAll(shopInfoRows));
        counts.put("systemSettings", insertAll(systemSettings));
        counts.put("stockMovements", insertAll(stockMovements));
        counts.put("stockTransfers", insertAll(stockTransfers));
        counts.put("promotions", insertAll(promotions));
        counts.put("productPriceHistory", insertAll(priceHistory));
        counts.put("countSheets", insertAll(countSheets));
        counts.put("glMappings", insertAll(glMappings));
        counts.put("invoiceSequences", insertAll(invoiceSequences));
        counts.put("purchaseSequences", insertAll(purchaseSequences));
        counts.put("customerSequences", insertAll(customerSequences));
        counts.put("transferSequences", insertAll(transferSequences));
        counts.put("periodCloses", insertAll(periodCloses));
        counts.put("inventoryLedger", insertAll(inventoryLedger));
        counts.put("profitLedger", insertAll(profitLedger));

        // 6️⃣ A replace took the file's history whole, so it takes the identity of
        //    the install that wrote it too: the next backup of these books is then
        //    the same shop's own file and merges instead of looking foreign. A file
        //    with no id says nothing, and nothing is not a reason to change.
        if (mode == ImportMode.REPLACE_ALL) {
            installIdentity.adopt(fileId);
        }

        // 7️⃣ Reset auto-increment counters so NEW records don't collide
        resetIdentityCounters();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mode", mode.name());
        result.put("counts", counts);
        return result;
    }

    /**
     * Puts one section's rows in as new records. The repository's save() would
     * merge instead, which asks Hibernate for a row this very restore has just
     * deleted — and a parent that owns a collection cannot be re-born that way:
     * the session keys its children by an identifier the insert has not produced
     * yet. So the exported ids are dropped and the database numbers the rows again
     * (step 6 then puts the counters ahead of them).
     */
    private <T> int insertAll(List<T> rows) {
        for (T row : rows) {
            restoreAsNew(row);
            entityManager.persist(row);
        }
        return rows.size();
    }

    /**
     * Makes one parsed row look like something this shop has never seen: the id it
     * carried (and its children carried) is dropped, and a parent the file pointed
     * at is asked of the session again. The wipe empties the session, so a
     * reference built while parsing no longer belongs to it — and Hibernate cannot
     * tell a row this restore is about to insert from one that never existed.
     */
    private void restoreAsNew(Object row) {
        for (Class<?> type = row.getClass(); type.getSuperclass() != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    if (field.isAnnotationPresent(jakarta.persistence.Id.class)
                            && field.isAnnotationPresent(jakarta.persistence.GeneratedValue.class)) {
                        field.set(row, null);
                    }
                    else if ((field.isAnnotationPresent(jakarta.persistence.OneToMany.class)
                            || field.isAnnotationPresent(jakarta.persistence.ManyToMany.class))
                            && field.get(row) instanceof java.util.Collection<?> children) {
                        children.forEach(this::restoreAsNew);
                    }
                    else if ((field.isAnnotationPresent(jakarta.persistence.ManyToOne.class)
                            || field.isAnnotationPresent(jakarta.persistence.OneToOne.class))
                            && field.get(row) != null && !entityManager.contains(field.get(row))) {
                        Object parent = identifierOf(field.get(row));
                        if (parent != null) {
                            field.set(row, entityManager.getReference(field.getType(), parent));
                        }
                    }
                } catch (ReflectiveOperationException impossibleOnOurOwnEntities) {
                    throw new IllegalStateException("Cannot restore a " + type.getSimpleName(), impossibleOnOurOwnEntities);
                }
            }
        }
    }

    /** The value of a row's {@code @Id} field, or null when it has none yet. */
    private static Object identifierOf(Object row) {
        for (Class<?> type = row.getClass(); type.getSuperclass() != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    field.setAccessible(true);
                    try {
                        return field.get(row);
                    } catch (IllegalAccessException impossibleOnOurOwnEntities) {
                        throw new IllegalStateException("Cannot read the id of a " + type.getSimpleName(),
                                impossibleOnOurOwnEntities);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Which install took this file. Blank for every file written before the id
     * existed, which is why a missing answer and a different one are refused the
     * same way: neither tells the shop anything.
     */
    private static String installIdOf(Map<String, Object> backup) {
        Object value = backup.get("installId");
        return value == null ? "" : value.toString().trim();
    }

    /**
     * Decides, for one merge, which of the file's rows this shop already has.
     *
     * <p>A file written by this install may be trusted on its numbering: an id
     * that exists here names the same row there, so skipping it is correct and
     * has always been the rule. A file from somewhere else cannot be trusted that
     * way — every database hands out 1, 2, 3 — and skipping on a number that
     * means two different things is how two shops become one set of books: the row
     * is left out, and the sale items and payments that point at it attach to
     * whatever happens to carry that number here.
     *
     * <p>So for a stranger's file an overlapping id is only skipped when the rows
     * agree about what they are by their own business key — an invoice number, a
     * product SKU, a customer code. Anything else is reported and the whole merge
     * is rolled back, because a mixed book looks exactly like a right one.
     */
    private static final class MergeGate {

        /** Enough for the shop to act on; the rest is counted, not listed. */
        private static final int MAX_REPORTED = 10;

        private final boolean rowsMustMatch;
        private final List<String> collisions = new ArrayList<>();
        private int beyondTheList;

        MergeGate(boolean rowsMustMatch) {
            this.rowsMustMatch = rowsMustMatch;
        }

        <T> List<T> keepOnlyNew(String section, List<T> incoming,
                                java.util.function.Supplier<List<T>> existingLoader,
                                java.util.function.Function<T, String> businessKey) {
            if (incoming == null || incoming.isEmpty()) return List.of();
            java.util.Set<Object> existingIds = new java.util.HashSet<>();
            java.util.Map<Object, T> existingRows = new java.util.HashMap<>();
            for (T existing : existingLoader.get()) {
                Object id = idOf(existing);
                if (id == null) continue;
                existingIds.add(id);
                existingRows.putIfAbsent(id, existing);
            }

            List<T> fresh = new ArrayList<>();
            for (T row : incoming) {
                Object id = idOf(row);
                if (id == null || !existingIds.contains(id)) {
                    fresh.add(row);
                    continue;
                }
                if (!rowsMustMatch || isTheSameRow(existingRows.get(id), row, businessKey)) {
                    continue;
                }
                if (collisions.size() < MAX_REPORTED) {
                    collisions.add(section + " id " + id);
                } else {
                    beyondTheList++;
                }
            }
            return fresh;
        }

        /**
         * Refuses the merge before any of it lands. Half a stranger's books in the
         * shop is worse than none, and the shop cannot tell which rows came from
         * which till afterwards.
         */
        void refuseRowsThatDoNotMatch() {
            if (collisions.isEmpty()) {
                return;
            }
            throw new com.bms.exception.StateConflictException(
                    "Nothing was merged. This file has rows with the same numbers as rows in this shop,"
                            + " but they are not the same rows: " + String.join(", ", collisions)
                            + (beyondTheList > 0 ? " (and " + beyondTheList + " more)" : "")
                            + ". Use Replace everything if the file is meant to take the place of this data.");
        }

        private static <T> boolean isTheSameRow(T here, T there,
                                                java.util.function.Function<T, String> businessKey) {
            if (businessKey == null) {
                return false;
            }
            String mine = businessKey.apply(here);
            return mine != null && !mine.isBlank() && mine.equalsIgnoreCase(businessKey.apply(there));
        }

        private static Object idOf(Object row) {
            try {
                return row.getClass().getMethod("getId").invoke(row);
            } catch (Exception impossibleOnOurOwnEntities) {
                return null;
            }
        }
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
            "sale_items", "sale_payments", "sales",
            "purchase_items", "purchase_expenses", "purchases",
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

        // Everything a complete backup describes is cleared in the same pass, and
        // only when the file actually carries that section — otherwise a restore
        // from an older file would delete the shop's drawers, counts and ledgers
        // and put nothing back.
        wipeIfPresent(tables, "purchase_payments", rawData.get("purchasePayments"));
        wipeIfPresent(tables, "cash_drawer_movements", rawData.get("cashDrawerMovements"));
        wipeIfPresent(tables, "cash_drawer_sessions", rawData.get("cashDrawerSessions"));
        wipeIfPresent(tables, "cash_drawers", rawData.get("cashDrawers"));
        wipeIfPresent(tables, "stock_transfer_items", rawData.get("stockTransfers"));
        wipeIfPresent(tables, "stock_transfers", rawData.get("stockTransfers"));
        wipeIfPresent(tables, "location_stock", rawData.get("locationStock"));
        wipeIfPresent(tables, "locations", rawData.get("locations"));
        wipeIfPresent(tables, "price_list_items", rawData.get("priceLists"));
        wipeIfPresent(tables, "price_lists", rawData.get("priceLists"));
        wipeIfPresent(tables, "promotion_tiers", rawData.get("promotions"));
        wipeIfPresent(tables, "promotions", rawData.get("promotions"));
        wipeIfPresent(tables, "count_sheet_items", rawData.get("countSheets"));
        wipeIfPresent(tables, "count_sheets", rawData.get("countSheets"));
        wipeIfPresent(tables, "gl_mappings", rawData.get("glMappings"));
        wipeIfPresent(tables, "gl_accounts", rawData.get("glAccounts"));
        wipeIfPresent(tables, "invoice_sequences", rawData.get("invoiceSequences"));
        wipeIfPresent(tables, "purchase_sequences", rawData.get("purchaseSequences"));
        wipeIfPresent(tables, "customer_sequences", rawData.get("customerSequences"));
        wipeIfPresent(tables, "transfer_sequences", rawData.get("transferSequences"));
        wipeIfPresent(tables, "period_close", rawData.get("periodCloses"));
        wipeIfPresent(tables, "inventory_ledger", rawData.get("inventoryLedger"));
        wipeIfPresent(tables, "profit_ledger", rawData.get("profitLedger"));

        // A replace cannot bring this install's own key back from the file — a
        // secret never travels in a backup — so the wipe takes the row out with
        // everything else and these few lines put it back afterwards.
        List<SystemSetting> secretsOfThisInstall = secretsOfThisInstall();

        jdbcTemplate.execute(disableFk);
        try {
            for (String table : tables) {
                try {
                    if (table.equals("system_settings")) {
                        jdbcTemplate.execute("DELETE FROM system_settings WHERE setting_key NOT LIKE 'accounting.%'");
                    } else {
                        jdbcTemplate.execute("DELETE FROM " + table);
                    }
                } catch (Exception ignored) {
                    // Table may not exist in an older installation — safe to skip
                }
            }
        } finally {
            jdbcTemplate.execute(enableFk);
        }
        restartCountersFromOne();
        // Drop any JPA entities currently cached; SQL deletes bypass the
        // persistence context and stale state would corrupt the upcoming saveAll.
        entityManager.clear();
        secretsOfThisInstall.forEach(entityManager::persist);
    }

    /**
     * The secret rows this install holds, copied out so the copy survives both the
     * SQL delete and the persistence-context clear. Handing the print agent a
     * stranger's key would point the counter queue at another shop's printer, and
     * handing it no key at all would stop this shop printing until its next
     * restart, so the value it was given when this install started is the one that
     * stays.
     */
    private List<SystemSetting> secretsOfThisInstall() {
        return systemSettingRepository.findAll().stream()
                .filter(setting -> SystemSettingService.SECRET_KEYS.contains(setting.getSettingKey()))
                .map(setting -> {
                    SystemSetting copy = new SystemSetting();
                    copy.setSettingKey(setting.getSettingKey());
                    copy.setSettingValue(setting.getSettingValue());
                    copy.setDescription(setting.getDescription());
                    copy.setDataType(setting.getDataType());
                    copy.setIsPublic(setting.getIsPublic());
                    return copy;
                })
                .toList();
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

    /**
     * Reads a section whose rows were flattened — association ids where the
     * entity has associations — and hands each entity to {@code link} so the
     * caller can put those associations back as id-only proxies. Sections the
     * backup does not contain read as empty lists, never as nulls.
     */
    @SuppressWarnings("unchecked")
    private <T> List<T> readFlat(ObjectMapper mapper, Object raw, Class<T> type,
                                 java.util.function.BiConsumer<T, Map<String, Object>> link) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<T> result = new ArrayList<>();
        for (Object item : list) {
            T entity = mapper.convertValue(item, type);
            link.accept(entity, (Map<String, Object>) item);
            result.add(entity);
        }
        return result;
    }

    /** An id-only stand-in for an association, or null when the row carries none. */
    private <R> R reference(Object id, Class<R> type) {
        Long numeric = numericOrNull(id);
        return numeric == null ? null : entityManager.getReference(type, numeric);
    }

    /** The nested rows of a flattened parent, in the order they were written. */
    private static List<Object> maps(Object rawItems) {
        return rawItems instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object row) {
        return row instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** True when the backup carries at least one row for the section. */
    private static boolean hasRows(Map<String, Object> rawData, String section) {
        return rawData.get(section) instanceof List<?> list && !list.isEmpty();
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
            // The drawer links are what let the shop reconcile the payment against
            // the shift it was taken in; a file from before they were written simply
            // has no keys for them.
            payment.setEmployeeShift(reference(row.get("employeeShiftId"), CashShift.class));
            payment.setDrawerMovement(reference(row.get("drawerMovementId"), CashDrawerMovement.class));
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
            // A refund paid from the drawer has to find that movement again, or the
            // restored shift no longer reconciles. Older files simply omit the keys.
            saleReturn.setEmployeeShift(reference(row.get("employeeShiftId"), CashShift.class));
            saleReturn.setDrawerMovement(reference(row.get("drawerMovementId"), CashDrawerMovement.class));
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
            // The hash sits behind @JsonIgnore, so Jackson reads every other field
            // of the row and drops that one: the restored shop would have nobody
            // who can log in.
            Object password = row.get("password");
            if (password != null) {
                user.setPassword(password.toString());
            }
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

    /** Every table whose rows the restore re-inserts under an id of its own. */
    private static final String[] COUNTER_TABLES = {"users", "categories", "customers", "suppliers", "products",
            "sales", "sale_items", "sale_payments", "purchases", "purchase_items", "purchase_expenses",
            "ar_payments", "receipt_customizations",
            "orders", "order_items", "order_sequences", "sale_returns", "sale_return_items",
            "expenses", "cash_shifts", "shop_info", "system_settings",
            "locations", "location_stock", "stock_movements", "stock_transfers",
            "stock_transfer_items", "price_lists", "price_list_items", "promotions",
            "promotion_tiers", "product_price_history", "purchase_payments",
            "cash_drawers", "cash_drawer_sessions", "cash_drawer_movements",
            "count_sheets", "count_sheet_items", "gl_accounts", "gl_mappings",
            "invoice_sequences", "purchase_sequences", "customer_sequences",
            "transfer_sequences", "inventory_ledger", "profit_ledger"};

    private void resetIdentityCounters() {
        String url = env.getProperty("spring.datasource.url", "");
        boolean isH2 = url.contains(":h2:");
        boolean isSqlite = url.contains(":sqlite:");
        for (String table : COUNTER_TABLES) {
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

    /**
     * After the wipe each table numbers from 1 again, so a restored row carries the
     * number the file gave it and the rows that point at it — children, journal
     * lines, drawer movements — still point at the right one. SQLite does this on
     * its own: without AUTOINCREMENT a rowid alias restarts once the table is empty.
     */
    private void restartCountersFromOne() {
        String url = env.getProperty("spring.datasource.url", "");
        boolean isH2 = url.contains(":h2:");
        if (url.contains(":sqlite:")) return;
        for (String table : COUNTER_TABLES) {
            try {
                jdbcTemplate.execute(isH2
                        ? "ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH 1"
                        : "ALTER TABLE " + table + " AUTO_INCREMENT = 1");
            } catch (Exception ignored) {
                // Table name may differ in your schema — safe to skip
            }
        }
    }
}