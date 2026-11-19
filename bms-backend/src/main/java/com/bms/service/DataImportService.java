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
        holdImportConnection();
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
        boolean carriesLedgers = rawData.get("inventoryLedger") instanceof List<?>
                && rawData.get("profitLedger") instanceof List<?>;

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
        List<Customer>  customers  = readCustomers(mapper, rawData.get("customers"));
        List<Supplier>  suppliers  = readSuppliers(mapper, rawData.get("suppliers"));
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
                .filter(this::isLocalSetting).map(SystemSetting::getId).toList();
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
        systemSettings.stream().filter(setting -> internalSettingIds.contains(setting.getId()))
                .forEach(setting -> setting.setId(null));

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
                    // Jackson has already populated this collection from the row's
                    // own "items" key, where a child carries only productId and price.
                    // Clearing first keeps one row per item instead of appending a
                    // second copy whose product association is null.
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
            boolean changedBalances = stateDiffers(products, productRepository)
                    || stateDiffers(customers, customerRepository)
                    || stateDiffers(sales, saleRepository)
                    || stateDiffers(purchases, purchaseRepository)
                    || stateDiffers(orders, orderRepository)
                    || stateDiffers(locationStock, locationStockRepository)
                    || stateDiffers(cashShifts, cashShiftRepository)
                    || stateDiffers(drawerSessions, cashDrawerSessionRepository);
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
            if (changedBalances && (!sales.isEmpty() || !purchases.isEmpty() || !saleReturns.isEmpty()
                    || !arPayments.isEmpty() || !purchasePayments.isEmpty() || !stockMovements.isEmpty()
                    || !drawerMovements.isEmpty() || !inventoryLedger.isEmpty() || !profitLedger.isEmpty()
                    || !orders.isEmpty() || !stockTransfers.isEmpty() || !countSheets.isEmpty())) {
                throw new com.bms.exception.StateConflictException(
                        "Nothing was merged. The backup's transaction balances differ from this shop. "
                                + "Use Replace everything to restore transactions and balances together.");
            }
        }

        if (!carriesLedgers && ledgerReadService.isActive() && (!products.isEmpty() || !sales.isEmpty()
                || !saleReturns.isEmpty() || !purchases.isEmpty() || !expenses.isEmpty()
                || !arPayments.isEmpty() || !customers.isEmpty())) {
            throw new com.bms.exception.BusinessException(
                    "Business-only imports cannot append reconciled accounting history. Use a complete database restore instead.");
        }

        if (mode == ImportMode.MERGE && !periodCloses.isEmpty()) {
            throw new com.bms.exception.StateConflictException(
                    "Merge cannot import period closures. Restore the complete backup or close periods locally.");
        }
        if (mode == ImportMode.MERGE || !carriesLedgers) {
            for (Sale sale : sales) {
                periodGuard.assertOpen(sale.getSaleDate());
                if (sale.getVoidedAt() != null) periodGuard.assertOpen(sale.getVoidedAt());
            }
            for (SaleReturn saleReturn : saleReturns) periodGuard.assertOpen(saleReturn.getReturnDate());
            for (Purchase purchase : purchases) periodGuard.assertOpen(purchase.getPurchaseDate());
            for (Expense expense : expenses) periodGuard.assertOpen(expense.getExpenseDate());
            for (ArPayment payment : arPayments) {
                periodGuard.assertOpen(payment.getPaymentDate());
                if (payment.getReversedAt() != null) periodGuard.assertOpen(payment.getReversedAt());
            }
            for (PurchasePayment payment : purchasePayments) periodGuard.assertOpen(payment.getPaymentDate());
            for (CashDrawerMovement movement : drawerMovements) periodGuard.assertOpen(movement.getOccurredAt());
            for (StockMovement movement : stockMovements) periodGuard.assertOpen(movement.getMovementDate());
            for (InventoryLedger entry : inventoryLedger) periodGuard.assertOpen(entry.getOccurredAt());
            for (ProfitLedger entry : profitLedger) periodGuard.assertOpen(entry.getOccurredAt());
        }

        Map<String, List<?>> sections = new LinkedHashMap<>();
        sections.put("users", users);
        sections.put("categories", categories);
        sections.put("customers", customers);
        sections.put("suppliers", suppliers);
        sections.put("glAccounts", glAccounts);
        sections.put("locations", locations);
        sections.put("cashDrawers", cashDrawers);
        sections.put("cashDrawerSessions", drawerSessions);
        sections.put("cashShifts", cashShifts);
        sections.put("cashDrawerMovements", drawerMovements);
        sections.put("products", products);
        sections.put("priceLists", priceLists);
        sections.put("locationStock", locationStock);
        sections.put("purchases", purchases);
        sections.put("sales", sales);
        sections.put("saleReturns", saleReturns);
        sections.put("receiptCustomizations", receiptCustomizations);
        sections.put("arPayments", arPayments);
        sections.put("purchasePayments", purchasePayments);
        sections.put("orders", orders);
        sections.put("orderSequences", orderSequences);
        sections.put("expenses", expenses);
        sections.put("shopInfo", shopInfoRows);
        sections.put("systemSettings", systemSettings);
        sections.put("stockMovements", stockMovements);
        sections.put("stockTransfers", stockTransfers);
        sections.put("promotions", promotions);
        sections.put("productPriceHistory", priceHistory);
        sections.put("countSheets", countSheets);
        sections.put("glMappings", glMappings);
        sections.put("invoiceSequences", invoiceSequences);
        sections.put("purchaseSequences", purchaseSequences);
        sections.put("customerSequences", customerSequences);
        sections.put("transferSequences", transferSequences);
        sections.put("periodCloses", periodCloses);
        sections.put("inventoryLedger", inventoryLedger);
        sections.put("profitLedger", profitLedger);

        BackupEntityInserter inserter = new BackupEntityInserter(entityManager, jdbcTemplate, env, shopTimeService);
        inserter.prepare(sections);
        periodGuard.lock();
        if (mode == ImportMode.REPLACE_ALL) {
            wipeAllData(rawData);
        }
        entityManager.clear();
        inserter.insertAll();
        Map<String, Integer> counts = new LinkedHashMap<>();
        sections.forEach((name, rows) -> counts.put(name, rows.size()));

        // 6️⃣ A replace took the file's history whole, so it takes the identity of
        //    the install that wrote it too: the next backup of these books is then
        //    the same shop's own file and merges instead of looking foreign. A file
        //    with no id says nothing, and nothing is not a reason to change.
        if (mode == ImportMode.REPLACE_ALL) {
            installIdentity.adopt(fileId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mode", mode.name());
        result.put("counts", counts);
        return result;
    }

    private <T> boolean stateDiffers(List<T> incoming,
                                     org.springframework.data.jpa.repository.JpaRepository<T, Long> repository) {
        var session = entityManager.unwrap(org.hibernate.engine.spi.SessionImplementor.class);
        for (T row : incoming) {
            var persister = session.getEntityPersister(null, row);
            Object id = persister.getIdentifier(row, session);
            if (id == null) continue;
            T existing = repository.findById(((Number) id).longValue()).orElse(null);
            if (existing == null) continue;
            Object[] left = persister.getPropertyValues(row);
            Object[] right = persister.getPropertyValues(existing);
            String[] names = persister.getPropertyNames();
            org.hibernate.type.Type[] types = persister.getPropertyTypes();
            for (int i = 0; i < names.length; i++) {
                if (types[i].isCollectionType() || names[i].equals("version")
                        || names[i].equals("createdAt") || names[i].equals("updatedAt")) continue;
                if (!types[i].isEqual(left[i], right[i], session.getFactory())) return true;
            }
        }
        return false;
    }

    private void holdImportConnection() {
        if (!env.getProperty("spring.datasource.url", "").contains(":h2:")) return;
        entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET EXCLUSIVE 1");
            }
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            try (java.sql.Statement statement = connection.createStatement()) {
                                statement.execute("SET EXCLUSIVE 0");
                            } catch (java.sql.SQLException failure) {
                                throw new IllegalStateException("Cannot release the restore connection", failure);
                            }
                        }
                    });
        });
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
        // product_images is deliberately NOT wiped: no export section carries it, so
        // a restore could never put those rows back. Product ids are preserved by a
        // restore, so the surviving rows still point at the right product. The phone
        // tables are wiped because the phone rows do travel (nested in the customer
        // and supplier JSON), and re-inserting them would otherwise collide on id.
        List<String> tables = new ArrayList<>(List.of(
            "ar_payments", "receipt_customizations",
            "sale_return_items", "sale_returns", "refund_items", "refunds",
            "sale_items", "sale_payments", "sales",
            "purchase_items", "purchase_expenses", "purchases",
            "order_items", "orders", "order_sequences",
            "stock_movements",
            "product_price_history",
            "customer_phones", "supplier_phones",
            "products", "categories", "customers", "suppliers"
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

        java.util.Set<String> existingTables = new java.util.HashSet<>();
        entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> {
            try (java.sql.ResultSet rows = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rows.next()) existingTables.add(rows.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
            }
        });
        List<String> deleteOrder = List.of(
                "gl_mappings", "ar_payments", "purchase_payments", "receipt_customizations",
                "sale_return_items", "sale_returns", "refund_items", "refunds", "sale_items", "sale_payments",
                "sales", "order_items", "orders", "stock_movements", "product_price_history",
                "stock_transfer_items", "stock_transfers", "count_sheet_items", "count_sheets",
                "location_stock", "promotion_tiers", "promotions", "price_list_items",
                "purchase_items", "purchase_expenses", "purchases", "customer_phones", "supplier_phones",
                "customers", "product_images", "products", "categories", "suppliers", "price_lists",
                "cash_drawer_movements", "cash_shifts", "cash_drawer_sessions", "cash_drawers",
                "locations", "expenses", "users", "gl_accounts", "shop_info", "system_settings",
                "order_sequences", "invoice_sequences", "purchase_sequences", "customer_sequences",
                "transfer_sequences", "period_close", "inventory_ledger", "profit_ledger");
        for (String table : deleteOrder) {
            if (!tables.contains(table) || !existingTables.contains(table)) continue;
            if (table.equals("system_settings")) {
                List<Long> protectedIds = systemSettingRepository.findAll().stream()
                        .filter(this::isLocalSetting).map(SystemSetting::getId).toList();
                String condition = protectedIds.isEmpty() ? "" : " WHERE id NOT IN ("
                        + String.join(",", java.util.Collections.nCopies(protectedIds.size(), "?")) + ")";
                jdbcTemplate.update("DELETE FROM system_settings" + condition, protectedIds.toArray());
            } else {
                jdbcTemplate.update("DELETE FROM " + table);
            }
        }
        entityManager.clear();
    }

    private boolean isLocalSetting(SystemSetting setting) {
        return setting.getSettingKey().startsWith("accounting.")
                || InstallIdentity.SETTING_KEY.equals(setting.getSettingKey())
                || SystemSettingService.SECRET_KEYS.contains(setting.getSettingKey());
    }

    private void wipeIfPresent(List<String> tables, String table, Object raw) {
        if (raw instanceof List<?>) {
            tables.add(table);
        }
    }

    private <T> List<T> readList(ObjectMapper mapper, Object raw, Class<T> type) {
        if (raw == null) return List.of();
        // Nested children (sale items and payments, purchase items, price-list
        // items, count-sheet items, promotion tiers) are the only copy of those
        // rows in the backup: there is no separate section for them. They must
        // therefore survive deserialization, and their back-references are
        // re-attached after reading, before anything is merged.
        return mapper.convertValue(raw,
                mapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    /**
     * Reads customers, keeping the phones that travel nested inside each one and
     * re-attaching their back-reference. Phones are the only copy of those rows in
     * the file (there is no separate section), and {@code customer_id} is NOT NULL,
     * so the owner must be in place before the child row is written.
     */
    private List<Customer> readCustomers(ObjectMapper mapper, Object raw) {
        if (raw == null) return List.of();
        List<Customer> result = mapper.convertValue(raw,
                mapper.getTypeFactory().constructCollectionType(List.class, Customer.class));
        for (Customer c : result) {
            if (c.getPhones() != null) {
                c.getPhones().forEach(phone -> phone.setCustomer(c));
            }
        }
        return result;
    }

    /**
     * Reads suppliers, keeping the phones nested inside each one and re-attaching
     * their back-reference — the same reason as {@link #readCustomers}: those rows
     * exist nowhere else in the file and {@code supplier_id} is NOT NULL.
     */
    private List<Supplier> readSuppliers(ObjectMapper mapper, Object raw) {
        if (raw == null) return List.of();
        List<Supplier> result = mapper.convertValue(raw,
                mapper.getTypeFactory().constructCollectionType(List.class, Supplier.class));
        for (Supplier s : result) {
            if (s.getPhones() != null) {
                s.getPhones().forEach(phone -> phone.setSupplier(s));
            }
        }
        return result;
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

    /**
     * An id-only stand-in for an association, or null when the row carries none.
     *
     * <p>A plain detached instance holding just the id, rather than a
     * {@code getReference} proxy: the write stage reads the id back to fill the
     * foreign key column, and a proxy from a cleared persistence context cannot be
     * read without initialising it.
     */
    private <R> R reference(Object id, Class<R> type) {
        Long numeric = numericOrNull(id);
        if (numeric == null) {
            return null;
        }
        try {
            R instance = type.getDeclaredConstructor().newInstance();
            type.getMethod("setId", Long.class).invoke(instance, numeric);
            return instance;
        } catch (Exception ex) {
            return entityManager.getReference(type, numeric);
        }
    }

    /** The nested rows of a flattened parent, in the order they were written. */
    private static List<Object> maps(Object rawItems) {
        return rawItems instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object row) {
        return row instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
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

}
