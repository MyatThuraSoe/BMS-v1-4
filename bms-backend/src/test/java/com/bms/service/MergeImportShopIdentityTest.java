package com.bms.service;

import com.bms.entity.Category;
import com.bms.entity.Customer;
import com.bms.entity.Product;
import com.bms.entity.Sale;
import com.bms.exception.StateConflictException;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V4: a merge decides what it already has by looking only at ids, and two shops
 * never made the same promise about what an id means. Import a file from another
 * install and every row whose id happens to match is skipped, while the children
 * of the rows that were not — sale items, payments, ledger lines — are attached to
 * whatever carries that id here. The books end up mixed and nothing says so.
 *
 * <p>So every install gets an id it never changes, every file carries it, and a
 * merge of a file that does not say this shop's own id has to be both confirmed by
 * a person and matched row against row before anything moves.
 */
@DataJpaTest
@ContextConfiguration(classes = MergeImportShopIdentityTest.JpaConfig.class)
@Import({DataExportService.class, DataImportService.class, ShopTimeService.class, InstallIdentity.class})
class MergeImportShopIdentityTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private DataExportService exporter;
    @Autowired private DataImportService importer;
    @Autowired private InstallIdentity identity;
    @Autowired private JdbcTemplate jdbc;

    // Whether a period is open is PeriodCloseGuardTest's business; here the
    // question is only whose file is being merged.
    @MockBean private PeriodGuard periodGuard;
    @MockBean private LedgerReadService ledgerReadService;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void startFromAnEmptyShop() {
        // A merge runs the identity-counter DDL, which commits the transaction
        // @DataJpaTest wanted rolled back, so the rows one test merged are still
        // there for the next one unless the database is emptied outright.
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'PUBLIC' AND table_type = 'BASE TABLE'", String.class);
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            tables.forEach(table -> jdbc.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY"));
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        em.clear();
        seedAShop();
    }

    private void seedAShop() {
        LocalDateTime at = LocalDate.of(2026, 11, 17).atTime(9, 30);

        Category grains = new Category();
        grains.setName("Grains");
        em.persist(grains);

        Product rice = new Product();
        rice.setSku("RICE-5KG");
        rice.setName("Rice 5kg");
        rice.setUnitPrice(new BigDecimal("9.00"));
        rice.setCostPrice(new BigDecimal("6.00"));
        em.persist(rice);

        Product beans = new Product();
        beans.setSku("BEANS-1KG");
        beans.setName("Beans 1kg");
        beans.setUnitPrice(new BigDecimal("15.00"));
        beans.setCostPrice(new BigDecimal("11.00"));
        em.persist(beans);

        Customer regular = new Customer();
        regular.setCustomerCode("CUST-0001");
        regular.setFirstName("Thida");
        regular.setLastName("Moe");
        regular.setPhone("09-0000-0000");
        em.persist(regular);

        com.bms.entity.Role role = new com.bms.entity.Role();
        role.setName(com.bms.entity.Role.RoleName.ROLE_CASHIER);
        em.persist(role);

        com.bms.entity.User cashier = new com.bms.entity.User();
        cashier.setUsername("cashier1");
        cashier.setEmail("cashier1@shop.test");
        cashier.setPassword("hash");
        cashier.setFirstName("Mai");
        cashier.setLastName("Soe");
        cashier.setRole(role);
        em.persist(cashier);

        Sale sale = new Sale();
        sale.setInvoiceNumber("INV-0001");
        sale.setCustomerDisplayName("Walk-in");
        sale.setCashierId(cashier.getId());
        sale.setSaleDate(at);
        sale.setSubtotal(new BigDecimal("9.00"));
        sale.setTotalAmount(new BigDecimal("9.00"));
        sale.setAmountPaid(new BigDecimal("9.00"));
        em.persist(sale);

        em.flush();
        // The install id is a database row, and these tests replace rows casually,
        // so make sure the one this process started with is in place.
        identity.currentId();
    }

    private Map<String, Object> document() throws Exception {
        return json.readValue(exporter.exportAllAsJson(), Map.class);
    }

    /** The same document taken on another till, in another shop. */
    private Map<String, Object> fromAnotherShop(Map<String, Object> backup) throws Exception {
        ObjectNode node = json.valueToTree(backup);
        node.put("installId", UUID.randomUUID().toString());
        return json.treeToValue(node, Map.class);
    }

    /** A document as a file written before the install id existed would look. */
    private Map<String, Object> withoutAnInstallId(Map<String, Object> backup) throws Exception {
        ObjectNode node = json.valueToTree(backup);
        node.remove("installId");
        return json.treeToValue(node, Map.class);
    }

    /**
     * Only the sections with a business key of their own, so the tests below are
     * about matching rows rather than about every other table in the shop also
     * colliding.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyRows(Map<String, Object> backup, String... sections) {
        Map<String, Object> trimmed = new LinkedHashMap<>(backup);
        Map<String, Object> data = (Map<String, Object>) backup.get("data");
        Map<String, Object> keptData = new LinkedHashMap<>();
        for (String section : sections) {
            keptData.put(section, data.get(section));
        }
        trimmed.put("data", keptData);
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> backup, String section) {
        return (List<Map<String, Object>>) ((Map<String, Object>) backup.get("data")).get(section);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void everyExportSaysWhichInstallTookIt() throws Exception {
        Map<String, Object> backup = document();

        assertEquals(identity.currentId(), backup.get("installId"),
                "a file has to name the shop it came out of");
        assertFalse(rows(backup, "systemSettings").stream()
                        .anyMatch(row -> InstallIdentity.SETTING_KEY.equals(row.get("settingKey"))),
                "the id travels as a field of the file, not as a setting an import could bring along");
    }

    @Test
    void aFileFromThisInstallStillMergesTheRowsThisShopLost() throws Exception {
        Map<String, Object> backup = document();
        jdbc.update("DELETE FROM products WHERE sku = 'BEANS-1KG'");
        em.clear();

        importer.importAll(backup, DataImportService.ImportMode.MERGE);
        em.flush();

        assertEquals(2, count("products"), "the row this shop no longer has comes back");
        assertEquals(1, count("sales"), "and the row it still has is not doubled");
    }

    @Test
    void aFileFromAnotherShopIsRefusedRatherThanFoldedIn() throws Exception {
        Map<String, Object> foreign = fromAnotherShop(document());

        StateConflictException ex = assertThrows(StateConflictException.class,
                () -> importer.importAll(foreign, DataImportService.ImportMode.MERGE));

        assertEquals(DataImportService.OTHER_SHOP_MESSAGE, ex.getMessage());
        assertEquals(2, count("products"), "a refusal moves nothing");
    }

    @Test
    void aFileOlderThanTheInstallIdIsTreatedAsAForeignOne() throws Exception {
        Map<String, Object> older = withoutAnInstallId(document());

        StateConflictException ex = assertThrows(StateConflictException.class,
                () -> importer.importAll(older, DataImportService.ImportMode.MERGE));

        assertEquals(DataImportService.OTHER_SHOP_MESSAGE, ex.getMessage());
    }

    @Test
    void aConfirmedForeignFileStillRefusesARowThatOnlyLooksLikeItsOwn() throws Exception {
        Map<String, Object> foreign = onlyRows(fromAnotherShop(document()),
                "products", "customers", "sales");
        rows(foreign, "products").get(0).put("sku", "SOMETHING-ELSE");

        StateConflictException ex = assertThrows(StateConflictException.class,
                () -> importer.importAll(foreign, DataImportService.ImportMode.MERGE, true));

        assertTrue(ex.getMessage().contains("products"), "names the table: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("1"), "and the id that collides: " + ex.getMessage());
        assertEquals("RICE-5KG", jdbc.queryForObject("SELECT sku FROM products WHERE id = 1", String.class),
                "nothing was overwritten on the way to the refusal");
        assertEquals(2, count("products"));
    }

    @Test
    void aConfirmedForeignFileWhoseRowsAreTheSameRowsMerges() throws Exception {
        Map<String, Object> foreign = onlyRows(fromAnotherShop(document()),
                "products", "customers", "sales");

        importer.importAll(foreign, DataImportService.ImportMode.MERGE, true);
        em.flush();

        assertEquals(2, count("products"), "a row the shop already has is skipped, not doubled");
        assertEquals(1, count("customers"));
        assertEquals(1, count("sales"));
    }

    @Test
    void replaceEverythingBecomesTheInstallTheFileCameFrom() throws Exception {
        String other = UUID.randomUUID().toString();
        Map<String, Object> foreign = fromAnotherShop(document());
        foreign.put("installId", other);
        assertNotEquals(identity.currentId(), other);

        importer.importAll(foreign, DataImportService.ImportMode.REPLACE_ALL);
        em.flush();

        assertEquals(other, identity.currentId(),
                "these books now are the books in that file, so a later merge of the same shop agrees");
    }

    @Test
    void aFileWithoutAnInstallIdLeavesThisInstallAloneOnAReplace() throws Exception {
        String before = identity.currentId();
        Map<String, Object> older = withoutAnInstallId(document());

        importer.importAll(older, DataImportService.ImportMode.REPLACE_ALL);
        em.flush();

        assertEquals(before, identity.currentId(),
                "there is nothing in the file to adopt, and inventing an id would be worse than keeping this one");
    }
}
