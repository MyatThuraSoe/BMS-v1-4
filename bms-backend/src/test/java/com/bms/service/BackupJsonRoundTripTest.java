package com.bms.service;

import com.bms.entity.ArPayment;
import com.bms.entity.CashDrawer;
import com.bms.entity.CashDrawerMovement;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.Category;
import com.bms.entity.CountSheet;
import com.bms.entity.CountSheetItem;
import com.bms.entity.CustomerSequence;
import com.bms.entity.DrawerMovementType;
import com.bms.entity.GlAccount;
import com.bms.entity.GlMapping;
import com.bms.entity.InventoryLedger;
import com.bms.entity.InvoiceSequence;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Location;
import com.bms.entity.LocationStock;
import com.bms.entity.PeriodClose;
import com.bms.entity.PriceList;
import com.bms.entity.PriceListItem;
import com.bms.entity.Product;
import com.bms.entity.ProductPriceHistory;
import com.bms.entity.ProfitLedger;
import com.bms.entity.Promotion;
import com.bms.entity.PromotionTier;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.PurchasePayment;
import com.bms.entity.PurchaseSequence;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SalePayment;
import com.bms.entity.SaleReturn;
import com.bms.entity.SaleReturnItem;
import com.bms.entity.StockMovement;
import com.bms.entity.StockTransfer;
import com.bms.entity.StockTransferItem;
import com.bms.entity.SystemSetting;
import com.bms.entity.TransferSequence;
import com.bms.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q5: the JSON document is what an H2 shop gets and the second half of what a
 * SQLite shop gets, so it has to restore the whole shop rather than the fourteen
 * tables an early version happened to know about.
 *
 * <p>These tests take a real database, export it, wreck it, restore it and count
 * the rows again — the only proof that matters about a backup is that what comes
 * back is what went in. The last two tests cover the case that bites in the
 * field: a file written before the new sections existed.
 */
@DataJpaTest
@ContextConfiguration(classes = BackupJsonRoundTripTest.JpaConfig.class)
@Import({DataExportService.class, DataImportService.class, ShopTimeService.class})
class BackupJsonRoundTripTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    /** The sections a pre-Q5 backup file has no key for at all. */
    private static final List<String> NEW_SECTIONS = List.of("invoiceSequences", "purchaseSequences",
            "customerSequences", "transferSequences", "locations", "locationStock", "stockMovements",
            "stockTransfers", "priceLists", "promotions", "productPriceHistory", "purchasePayments",
            "cashDrawers", "cashDrawerSessions", "cashDrawerMovements", "countSheets", "glAccounts",
            "glMappings", "periodCloses", "inventoryLedger", "profitLedger");

    @Autowired private TestEntityManager em;
    @Autowired private DataExportService exporter;
    @Autowired private DataImportService importer;
    @Autowired private JdbcTemplate jdbc;

    /**
     * A restore runs DDL — the identity counters — and DDL commits the transaction
     * {@code @DataJpaTest} wanted rolled back, so the rows one test restored are
     * still there for the next one. Empty the database outright and number it
     * again from 1: the same shop every test expects to seed.
     */
    @BeforeEach
    void startFromAnEmptyShop() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'PUBLIC' AND table_type = 'BASE TABLE'", String.class);
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            // RESTART is not the default: without it the ids this test seeds would
            // continue from the rows just deleted, and a restore keys itself to the
            // ids the file carries.
            tables.forEach(table -> jdbc.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY"));
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        em.clear();
    }

    // Whether a period is open is PeriodCloseGuardTest's business; here the
    // question is only what the document carries and what comes back from it.
    @MockBean private PeriodGuard periodGuard;
    @MockBean private LedgerReadService ledgerReadService;

    private final ObjectMapper json = new ObjectMapper();

    /** Backup section → the table its rows must come back into. */
    private final Map<String, Class<?>> sections = new LinkedHashMap<>();

    private void track(String section, Class<?> entity) {
        sections.put(section, entity);
    }

    private long rowsOf(Class<?> entity) {
        return em.getEntityManager()
                .createQuery("select count(e) from " + entity.getSimpleName() + " e", Long.class)
                .getSingleResult();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> document() throws Exception {
        return json.readValue(exporter.exportAllAsJson(), Map.class);
    }

    private static List<Map<String, Object>> rows(Map<String, Object> backup, String section) {
        return (List<Map<String, Object>>) section(backup, section);
    }

    private static Object section(Map<String, Object> backup, String name) {
        return ((Map<?, ?>) backup.get("data")).get(name);
    }

    // --------------------------------------------------------------------- seed

    private Category rice;
    private Category pulses;
    private Product product;
    private User cashier;
    private Sale sale;
    private SaleItem riceLine;
    private Location shop;
    private Location backroom;

    /** One row of most of the shop, and two sales so the invoice counter has a last number. */
    private void seedAShop() {
        track("categories", Category.class);
        track("products", Product.class);
        track("sales", Sale.class);
        track("saleReturns", SaleReturn.class);
        track("purchases", Purchase.class);
        track("purchasePayments", PurchasePayment.class);
        track("arPayments", ArPayment.class);
        track("users", User.class);
        track("systemSettings", SystemSetting.class);
        track("invoiceSequences", InvoiceSequence.class);
        track("purchaseSequences", PurchaseSequence.class);
        track("customerSequences", CustomerSequence.class);
        track("transferSequences", TransferSequence.class);
        track("locations", Location.class);
        track("locationStock", LocationStock.class);
        track("stockMovements", StockMovement.class);
        track("stockTransfers", StockTransfer.class);
        track("priceLists", PriceList.class);
        track("promotions", Promotion.class);
        track("productPriceHistory", ProductPriceHistory.class);
        track("cashDrawers", CashDrawer.class);
        track("cashDrawerSessions", CashDrawerSession.class);
        track("cashDrawerMovements", CashDrawerMovement.class);
        track("countSheets", CountSheet.class);
        track("glAccounts", GlAccount.class);
        track("glMappings", GlMapping.class);
        track("periodCloses", PeriodClose.class);
        track("inventoryLedger", InventoryLedger.class);
        track("profitLedger", ProfitLedger.class);

        LocalDate today = LocalDate.of(2026, 11, 17);
        LocalDateTime at = today.atTime(9, 30);

        rice = persist(new Category(), c -> c.setName("Grains"));
        pulses = persist(new Category(), c -> c.setName("Pulses"));

        product = persist(new Product(), p -> {
            p.setSku("RICE-5KG");
            p.setName("Rice 5kg");
            p.setUnitPrice(new BigDecimal("9.00"));
            p.setCostPrice(new BigDecimal("6.00"));
            p.setStockQuantity(40);
        });
        Product beans = persist(new Product(), p -> {
            p.setSku("BEANS-1KG");
            p.setName("Beans 1kg");
            p.setUnitPrice(new BigDecimal("15.00"));
            p.setCostPrice(new BigDecimal("11.00"));
            p.setStockQuantity(10);
        });

        Role role = persist(new Role(), r -> r.setName(Role.RoleName.ROLE_CASHIER));
        cashier = persist(new User(), u -> {
            u.setUsername("cashier1");
            u.setEmail("cashier1@shop.test");
            u.setPassword("hash");
            u.setFirstName("Mai");
            u.setLastName("Soe");
            u.setRole(role);
        });

        sale = persist(new Sale(), s -> {
            s.setInvoiceNumber("INV-0001");
            s.setCustomerDisplayName("Walk-in");
            s.setCashierId(cashier.getId());
            s.setSaleDate(at);
            s.setSubtotal(new BigDecimal("24.00"));
            s.setTotalAmount(new BigDecimal("24.00"));
            s.setAmountPaid(new BigDecimal("24.00"));
        });
        riceLine = persist(new SaleItem(), line -> {
            line.setSale(sale);
            line.setProduct(product);
            line.setQuantity(2);
            line.setUnitPrice(new BigDecimal("9.00"));
            line.setTotalPrice(new BigDecimal("18.00"));
        });
        persist(new SaleItem(), line -> {
            line.setSale(sale);
            line.setProduct(beans);
            line.setQuantity(1);
            line.setUnitPrice(new BigDecimal("15.00"));
            line.setTotalPrice(new BigDecimal("15.00"));
        });
        Sale creditSale = persist(new Sale(), s -> {
            s.setInvoiceNumber("INV-0002");
            s.setCustomerDisplayName("Walk-in");
            s.setCashierId(cashier.getId());
            s.setSaleDate(at.plusHours(1));
            s.setSubtotal(new BigDecimal("9.00"));
            s.setTotalAmount(new BigDecimal("9.00"));
            s.setAmountPaid(new BigDecimal("0.00"));
            s.setPaymentStatus(Sale.PaymentStatus.UNPAID);
        });

        SaleReturn saleReturn = persist(new SaleReturn(), r -> {
            r.setSale(sale);
            r.setReturnedBy(cashier);
            r.setReturnDate(at.plusDays(1));
            r.setReason("Damaged pack");
            r.setTotalReturnAmount(new BigDecimal("9.00"));
        });
        persist(new SaleReturnItem(), item -> {
            item.setSaleReturn(saleReturn);
            item.setSaleItem(riceLine);
            item.setQuantityReturned(1);
            item.setReturnAmount(new BigDecimal("9.00"));
        });

        Purchase purchase = persist(new Purchase(), p -> {
            p.setPurchaseNumber("PO-0001");
            p.setPurchaseDate(today);
            p.setSubtotal(new BigDecimal("60.00"));
            p.setTotalAmount(new BigDecimal("60.00"));
            p.setCreatedBy(cashier.getId());
        });
        persist(new PurchaseItem(), item -> {
            item.setPurchase(purchase);
            item.setProduct(product);
            item.setQuantity(10);
            item.setUnitCost(new BigDecimal("6.00"));
            item.setTotalCost(new BigDecimal("60.00"));
        });
        persist(new PurchasePayment(), payment -> {
            payment.setPurchase(purchase);
            payment.setAmount(new BigDecimal("20.00"));
            payment.setPaymentDate(at);
            payment.setRecordedBy(cashier);
        });

        // The switch Q5 adds is itself a system setting: restoring must not lose it.
        persist(new SystemSetting(), s -> {
            s.setSettingKey(BackupService.BACKUP_FORMAT_SETTING);
            s.setSettingValue("BOTH");
        });

        persist(new InvoiceSequence(), s -> {
            s.setSequenceDate(today);
            s.setLastNumber(2);
        });
        persist(new PurchaseSequence(), s -> {
            s.setSequenceDate(today);
            s.setLastNumber(1);
        });
        persist(new CustomerSequence(), s -> {
            s.setSequenceDate(today);
            s.setLastNumber(1);
        });
        persist(new TransferSequence(), s -> {
            s.setSequenceDate(today);
            s.setLastNumber(1);
        });

        shop = persist(new Location(), l -> {
            l.setCode("MAIN");
            l.setName("Main shop");
        });
        backroom = persist(new Location(), l -> {
            l.setCode("BACK");
            l.setName("Backroom");
            l.setType(Location.LocationType.BACKROOM);
        });
        persist(new LocationStock(), stock -> {
            stock.setLocation(shop);
            stock.setProduct(product);
            stock.setQuantity(30);
        });
        persist(new LocationStock(), stock -> {
            stock.setLocation(backroom);
            stock.setProduct(product);
            stock.setQuantity(10);
        });

        persist(new StockMovement(), movement -> {
            movement.setProduct(product);
            movement.setMovementType(StockMovement.MovementType.IN);
            movement.setQuantity(40);
            movement.setReferenceType(StockMovement.ReferenceType.PURCHASE);
            movement.setReferenceId(purchase.getId());
            movement.setCreatedBy(cashier);
            movement.setMovementDate(at);
        });

        StockTransfer transfer = persist(new StockTransfer(), t -> {
            t.setTransferNumber("TR-0001");
            t.setFromLocation(shop);
            t.setToLocation(backroom);
            t.setStatus(StockTransfer.TransferStatus.COMPLETED);
        });
        persist(new StockTransferItem(), item -> {
            item.setTransfer(transfer);
            item.setProduct(product);
            item.setQuantity(10);
        });

        PriceList priceList = persist(new PriceList(), p -> p.setName("Wholesale"));
        persist(new PriceListItem(), item -> {
            item.setPriceList(priceList);
            item.setProduct(product);
            item.setPrice(new BigDecimal("8.00"));
        });
        persist(new PriceListItem(), item -> {
            item.setPriceList(priceList);
            item.setProduct(beans);
            item.setPrice(new BigDecimal("13.50"));
        });

        Promotion promotion = persist(new Promotion(), p -> {
            p.setName("Rice week");
            p.setProduct(product);
            p.setDiscountValue(new BigDecimal("5.00"));
        });
        persist(new PromotionTier(), tier -> {
            tier.setPromotion(promotion);
            tier.setMinQuantity(5);
            tier.setDiscountPercent(new BigDecimal("7.50"));
        });

        persist(new ProductPriceHistory(), h -> {
            h.setProduct(product);
            h.setOldPrice(new BigDecimal("8.50"));
            h.setNewPrice(new BigDecimal("9.00"));
            h.setChangedBy(cashier);
            h.setPriceType(ProductPriceHistory.PriceType.SELLING);
            h.setSource(ProductPriceHistory.Source.MANUAL_ADJUSTMENT);
        });

        CashDrawer drawer = persist(new CashDrawer(), d -> {
            d.setCode("D1");
            d.setName("Front counter");
        });
        CashDrawerSession session = persist(new CashDrawerSession(), s -> {
            s.setDrawer(drawer);
            s.setOpenedBy(cashier);
            s.setOpenedAt(at);
            s.setOpeningFloat(new BigDecimal("50.00"));
        });
        CashDrawerMovement movement = persist(new CashDrawerMovement(), m -> {
            m.setDrawerSession(session);
            m.setMovementType(DrawerMovementType.OPENING_FLOAT);
            m.setAmount(new BigDecimal("50.00"));
            m.setPerformedBy(cashier);
            m.setOccurredAt(at);
        });

        // The money trail: a cash line paid out of the drawer against the first
        // invoice, and a part-payment recorded against the credit sale. The cash
        // line travels inside the sale and the receivable in its own section, and
        // both point at drawer rows the restore inserts first.
        persist(new SalePayment(), p -> {
            p.setSale(sale);
            p.setPaymentMethod(PaymentMethod.CASH);
            p.setAmount(new BigDecimal("24.00"));
            p.setPaidAt(at);
            p.setDrawerSession(session);
            p.setDrawerMovement(movement);
        });
        persist(new ArPayment(), p -> {
            p.setInvoice(creditSale);
            p.setPaymentMethod(PaymentMethod.CARD);
            p.setAmount(new BigDecimal("4.00"));
            p.setPaymentDate(at);
            p.setRecordedBy(cashier);
            p.setDrawerMovement(movement);
        });
        // The return refunded cash out of that drawer, so its own trail points at
        // the same movement.
        saleReturn.setRefundAmount(new BigDecimal("9.00"));
        saleReturn.setRefundMethod(PaymentMethod.CASH);
        saleReturn.setDrawerMovement(movement);

        CountSheet sheet = persist(new CountSheet(), s -> {
            s.setSheetNumber("CS-0001");
            s.setScheduledDate(today);
            s.setCreatedBy(cashier.getId());
        });
        persist(new CountSheetItem(), item -> {
            item.setCountSheet(sheet);
            item.setProduct(product);
            item.setSystemQuantity(40);
            item.setCountedQuantity(39);
            item.setVariance(-1);
        });

        GlAccount inventory = persist(new GlAccount(), a -> {
            a.setCode("1300");
            a.setName("Inventory");
            a.setType(GlAccount.AccountType.ASSET);
        });
        persist(new GlMapping(), m -> {
            m.setEventType(GlMapping.EventType.INVENTORY);
            m.setAccount(inventory);
        });

        persist(new PeriodClose(), c -> {
            c.setPeriod("2026-10");
            c.setClosedAt(at);
            c.setClosedBy(cashier.getId());
            c.setFrozenRevenue(new BigDecimal("1200.00"));
        });
        persist(new InventoryLedger(), row -> {
            row.setProductId(product.getId());
            row.setEventType(InventoryLedger.EventType.RECEIPT);
            row.setSourceType("PURCHASE");
            row.setSourceId(purchase.getId());
            row.setQtyDelta(40);
            row.setUnitCost(new BigDecimal("6.000000"));
            row.setValueDelta(new BigDecimal("240.000000"));
            row.setQtyAfter(40);
            row.setValueAfter(new BigDecimal("240.000000"));
            row.setUnitCostAfter(new BigDecimal("6.000000"));
            row.setOccurredAt(at);
            row.setPostedAt(at);
            row.setCreatedBy(cashier.getId());
        });
        persist(new ProfitLedger(), row -> {
            row.setPeriod("2026-11");
            row.setEventType(ProfitLedger.EventType.SALE);
            row.setSourceType("SALE");
            row.setSourceId(sale.getId());
            row.setProductId(product.getId());
            row.setNetRevenue(new BigDecimal("24.0000"));
            row.setCogs(new BigDecimal("12.0000"));
            row.setOccurredAt(at);
            row.setPostedAt(at);
        });

        em.flush();
        em.clear();
    }

    private <T> T persist(T entity, java.util.function.Consumer<T> shape) {
        shape.accept(entity);
        em.persist(entity);
        return entity;
    }

    /** The same document with the sections an older LumiPOS never wrote removed. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asAnOlderFile(Map<String, Object> backup) throws Exception {
        JsonNode tree = json.readTree(json.writeValueAsBytes(backup));
        NEW_SECTIONS.forEach(name -> ((ObjectNode) tree.get("data")).remove(name));
        return json.convertValue(tree, Map.class);
    }

    // -------------------------------------------------------------------- tests

    @Test
    void aCompleteBackupWritesAndRestoresEveryTableItClaimsToCover() throws Exception {
        seedAShop();
        Map<String, Object> backup = document();

        for (Map.Entry<String, Class<?>> section : sections.entrySet()) {
            long live = rowsOf(section.getValue());
            assertTrue(live > 0, section.getKey() + " was never seeded, so this test proves nothing");
            assertEquals(live, rows(backup, section.getKey()).size(),
                    section.getKey() + " is not fully written to the backup");
        }

        // Wreck the shop the way a bad restore afternoon does: a row edited after
        // the backup was taken, and a row the backup has never heard of.
        em.find(Product.class, product.getId()).setName("Sold out rice");
        persist(new Category(), c -> c.setName("Added after the backup"));
        em.flush();
        em.clear();

        importer.importAll(backup, DataImportService.ImportMode.REPLACE_ALL);
        em.flush();
        em.clear();

        for (Map.Entry<String, Class<?>> section : sections.entrySet()) {
            assertEquals(rows(backup, section.getKey()).size(), rowsOf(section.getValue()),
                    section.getKey() + " did not come back with the rows the backup carried");
        }
        assertEquals("Rice 5kg", em.find(Product.class, product.getId()).getName(),
                "the backup's row wins over the one edited after it was taken");
        assertEquals(2, rowsOf(Category.class), "REPLACE_ALL means the row added afterwards is gone");
    }

    @Test
    void aRestorePutsTheAssociationsBackAndNotJustTheRows() throws Exception {
        seedAShop();
        Map<String, Object> backup = document();

        importer.importAll(backup, DataImportService.ImportMode.REPLACE_ALL);
        em.flush();
        em.clear();

        // The flattened sections carry ids where the entity has associations; a
        // restore that writes them back unlinked leaves rows pointing at nothing.
        Sale restored = em.find(Sale.class, sale.getId());
        assertEquals(2, restored.getItems().size());
        assertEquals(cashier.getId(), restored.getCashierId());

        SaleReturn restoredReturn = only(SaleReturn.class);
        assertEquals(sale.getId(), restoredReturn.getSale().getId(), "the return found its invoice again");
        assertEquals(1, restoredReturn.getItems().size());
        assertEquals(riceLine.getId(), restoredReturn.getItems().get(0).getSaleItem().getId(),
                "the returned line is still the line that was sold");
        assertEquals(PaymentMethod.CASH, restoredReturn.getRefundMethod());
        assertEquals(0, restoredReturn.getRefundAmount().compareTo(new BigDecimal("9.00")));
        assertEquals(only(CashDrawerMovement.class).getId(), restoredReturn.getDrawerMovement().getId(),
                "the refund still points at the drawer movement that paid it");

        assertEquals(2, only(PriceList.class).getItems().size());
        assertNotNull(only(PriceList.class).getItems().get(0).getProduct());
        assertEquals(1, only(Promotion.class).getTiers().size());
        assertEquals(product.getId(), only(Promotion.class).getProduct().getId());
        assertEquals(1, only(CountSheet.class).getItems().size());
        assertEquals("1300", only(GlMapping.class).getAccount().getCode());
        assertEquals(cashier.getId(), only(StockMovement.class).getCreatedBy().getId());

        LocationStock stock = em.getEntityManager().createQuery(
                "select s from LocationStock s where s.quantity = 30", LocationStock.class).getSingleResult();
        assertEquals("MAIN", stock.getLocation().getCode());
        assertEquals("RICE-5KG", stock.getProduct().getSku());

        // The cash trail is the part a shop notices missing: a payment that lost
        // its invoice or its drawer movement no longer reconciles the shift.
        SalePayment cash = only(SalePayment.class);
        assertEquals(sale.getId(), cash.getSale().getId());
        assertEquals(only(CashDrawerSession.class).getId(), cash.getDrawerSession().getId(),
                "the cash line still belongs to the session it was paid into");
        ArPayment received = only(ArPayment.class);
        assertNotNull(received.getInvoice(), "the receivable lost the invoice it was paid against");
        assertEquals(PaymentMethod.CARD, received.getPaymentMethod());
        assertEquals(cash.getDrawerMovement().getId(), received.getDrawerMovement().getId(),
                "both money lines still point at the same drawer movement");
    }

    @Test
    void aRestoredShopDoesNotReissueInvoiceNumbersItAlreadyGaveAway() throws Exception {
        seedAShop();
        Map<String, Object> backup = document();

        importer.importAll(backup, DataImportService.ImportMode.REPLACE_ALL);
        em.flush();
        em.clear();

        int lastPrinted = em.getEntityManager()
                .createQuery("select s.invoiceNumber from Sale s", String.class).getResultList()
                .stream().map(number -> Integer.parseInt(number.substring(4)))
                .max(Integer::compare).orElseThrow();
        int nextFree = only(InvoiceSequence.class).getLastNumber();

        assertTrue(nextFree >= lastPrinted,
                "the counter is at " + nextFree + " but " + lastPrinted + " has already been printed");
        assertEquals(1, rowsOf(PurchaseSequence.class), "every counter survives, not just the invoice one");
        assertEquals(1, rowsOf(CustomerSequence.class));
        assertEquals(1, rowsOf(TransferSequence.class));
    }

    @Test
    void theClosedPeriodAndTheLedgersItVouchesForComeBackTogether() throws Exception {
        seedAShop();
        Map<String, Object> backup = document();

        importer.importAll(backup, DataImportService.ImportMode.REPLACE_ALL);
        em.flush();
        em.clear();

        assertEquals("2026-10", only(PeriodClose.class).getPeriod());
        assertEquals(1, rowsOf(InventoryLedger.class));
        assertEquals(1, rowsOf(ProfitLedger.class));
        assertEquals("BOTH", only(SystemSetting.class).getSettingValue(),
                "the shop's backup choice survives a restore of itself");
    }

    @Test
    void anOlderFileMergesWithoutEmptyingTheTablesItKnowsNothingAbout() throws Exception {
        seedAShop();
        Map<String, Object> olderFile = asAnOlderFile(document());

        importer.importAll(olderFile, DataImportService.ImportMode.MERGE);
        em.flush();
        em.clear();

        assertEquals(2, rowsOf(Category.class));
        assertEquals(2, rowsOf(Sale.class));
        for (String section : NEW_SECTIONS) {
            assertTrue(rowsOf(sections.get(section)) > 0,
                    section + " was emptied by a file that never described it");
        }
    }

    @Test
    void anOlderFileCannotReplaceLedgersItNeverDescribed() throws Exception {
        seedAShop();
        Map<String, Object> olderFile = asAnOlderFile(document());

        com.bms.exception.BusinessException failure = assertThrows(
                com.bms.exception.BusinessException.class,
                () -> importer.importAll(olderFile, DataImportService.ImportMode.REPLACE_ALL));

        assertTrue(failure.getMessage().contains("ledger"), "tells the shop why: " + failure.getMessage());
        assertEquals(1, rowsOf(ProfitLedger.class), "nothing was deleted on the way to the refusal");
    }

    private <T> T only(Class<T> entity) {
        return em.getEntityManager()
                .createQuery("select e from " + entity.getSimpleName() + " e", entity)
                .getSingleResult();
    }
}
