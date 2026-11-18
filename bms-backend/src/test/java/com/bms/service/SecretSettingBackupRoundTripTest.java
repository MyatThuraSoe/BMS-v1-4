package com.bms.service;

import com.bms.entity.SystemSetting;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V5: a backup is a copy of the shop, and the key to the counter print queue is
 * not part of the shop's data.
 *
 * <p>The token the print agent authenticates with sat in system_settings with
 * everything else, so it went up to Google Drive inside every backup, and
 * restoring a file from another till put that till's key into this one. A backup
 * now leaves it out, and an import refuses to bring one in: a restored shop makes
 * its own.
 */
@DataJpaTest
@ContextConfiguration(classes = SecretSettingBackupRoundTripTest.JpaConfig.class)
@Import({DataExportService.class, DataImportService.class, ShopTimeService.class, InstallIdentity.class})
class SecretSettingBackupRoundTripTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    private static final String TOKEN_KEY = "print.agent.token";
    private static final String OWN_TOKEN = "0a1b2c3d4e5f60718293a4b5c6d7e8f9";
    private static final String FOREIGN_TOKEN = "f9e8d7c6b5a439281706f5e4d3c2b1a0";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private DataExportService exporter;

    @Autowired
    private DataImportService importer;

    @Autowired
    private InstallIdentity identity;

    @Autowired
    private JdbcTemplate jdbc;

    // Whether a period is open is another test's business; here the question is
    // only which setting rows travel in a backup file.
    @MockBean
    private PeriodGuard periodGuard;

    @MockBean
    private LedgerReadService ledgerReadService;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void storeOneSecretAndOneOrdinarySetting() {
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

        em.persist(setting(TOKEN_KEY, OWN_TOKEN));
        em.persist(setting("business_name", "LumiPOS Corner Shop"));
        em.flush();
        identity.currentId();
    }

    private SystemSetting setting(String key, String value) {
        SystemSetting row = new SystemSetting();
        row.setSettingKey(key);
        row.setSettingValue(value);
        row.setDataType(SystemSetting.DataType.STRING);
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> document() throws Exception {
        return json.readValue(exporter.exportAllAsJson(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> backup, String section) {
        return (List<Map<String, Object>>) ((Map<String, Object>) backup.get("data")).get(section);
    }

    /** A file from another till, carrying that till's own key. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withAForeignToken(Map<String, Object> backup) {
        ArrayNode settings = (ArrayNode) json.valueToTree(rows(backup, "systemSettings"));
        ObjectNode foreign = settings.addObject();
        foreign.put("id", 999_999L);
        foreign.put("settingKey", TOKEN_KEY);
        foreign.put("settingValue", FOREIGN_TOKEN);
        foreign.put("dataType", "STRING");
        ((Map<String, Object>) backup.get("data")).put("systemSettings", json.convertValue(settings, List.class));
        return backup;
    }

    private String storedValueOf(String key) {
        return jdbc.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key = ?",
                String.class, key);
    }

    private int storedCountOf(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM system_settings WHERE setting_key = ?",
                Integer.class, key);
    }

    @Test
    void anExportLeavesThePrintAgentTokenAtHome() throws Exception {
        String file = new String(exporter.exportAllAsJson(), java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(file.contains(OWN_TOKEN), "the key to the print queue is not shop data");
        assertFalse(file.contains(TOKEN_KEY));
        assertTrue(file.contains("LumiPOS Corner Shop"),
                "the rest of the settings section still travels");
    }

    @Test
    void mergingAFileDoesNotTakeAnotherTillsKey() throws Exception {
        importer.importAll(withAForeignToken(document()), DataImportService.ImportMode.MERGE);

        assertEquals(1, storedCountOf(TOKEN_KEY));
        assertEquals(OWN_TOKEN, storedValueOf(TOKEN_KEY),
                "this install keeps the key its own agent was given");
    }

    @Test
    void restoringAFileNeverPutsAForeignKeyInThisShop() throws Exception {
        importer.importAll(withAForeignToken(document()), DataImportService.ImportMode.REPLACE_ALL);
        em.flush();

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_settings WHERE setting_value = ?",
                Integer.class, FOREIGN_TOKEN), "no row of this shop holds another install's key");
        assertEquals(1, storedCountOf(TOKEN_KEY));
        assertEquals(OWN_TOKEN, storedValueOf(TOKEN_KEY),
                "the wipe of the shop's rows takes the print agent key with them, so it comes back");
    }

    @Test
    void anOrdinarySettingStillSurvivesTheRoundTrip() throws Exception {
        importer.importAll(document(), DataImportService.ImportMode.MERGE);

        assertEquals("LumiPOS Corner Shop", storedValueOf("business_name"));
    }
}
