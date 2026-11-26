package com.bms.sqlite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQLite schema is created here instead of by Hibernate, so these tests run the real
 * migrator against real SQLite files: a script that does not execute, or that quietly
 * loses a shop's rows or columns on upgrade, is the failure this design can produce.
 */
class SqliteSchemaMigratorTest {

    private static DataSource sqlite(Path file) {
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file);
        return dataSource;
    }

    private static void migrate(DataSource dataSource) throws Exception {
        new SqliteSchemaMigrator(dataSource, new DefaultResourceLoader()).afterPropertiesSet();
    }

    private static String script() throws IOException {
        Resource resource = new DefaultResourceLoader().getResource(SqliteSchemaMigrator.SCRIPT_LOCATION);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Set<String> tables(Connection connection) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static Set<String> columnsOf(Connection connection, String table) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (ResultSet rs = connection.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    @Test
    void aFreshDatabaseEndsWithEveryTableAndIndexTheScriptDeclares(@TempDir Path dir) throws Exception {
        Map<String, SqliteSchemaMigrator.Table> expected = SqliteSchemaMigrator.tables(script());
        assertTrue(expected.size() > 40, "the baseline script must cover the whole model");

        Path file = dir.resolve("lumipos.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(Set.of(), tables(connection), "the file starts empty");
        }

        migrate(sqlite(file));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(new TreeSet<>(expected.keySet()), tables(connection));
            try (ResultSet rs = connection.createStatement()
                    .executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'index'")) {
                rs.next();
                assertTrue(rs.getInt(1) > 100, "indexes from the script must exist, found " + rs.getInt(1));
            }
        }
    }

    @Test
    void anOlderDatabaseGainsItsMissingColumnsWithoutLosingItsRows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            // Customers as it stood before balances and soft deletes existed.
            statement.execute("create table customers (id integer primary key autoincrement,"
                    + " first_name varchar(255) not null, phone varchar(255))");
            statement.execute("insert into customers (first_name, phone) values ('Aung','09-111')");
        }

        migrate(sqlite(file));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            Set<String> columns = columnsOf(connection, "customers");
            assertTrue(columns.contains("credit_balance"), "credit_balance must be added");
            assertTrue(columns.contains("deleted_at"), "deleted_at must be added");
            try (ResultSet rs = connection.createStatement()
                    .executeQuery("select first_name, credit_balance, deleted_at from customers")) {
                assertTrue(rs.next(), "the pre-existing customer must survive the upgrade");
                assertEquals("Aung", rs.getString(1));
                assertNull(rs.getString(2));
                assertNull(rs.getString(3));
            }
        }
    }

    @Test
    void aColumnWithADefaultIsAddedWithThatDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        Map<String, SqliteSchemaMigrator.Table> expected = SqliteSchemaMigrator.tables(script());
        SqliteSchemaMigrator.Column returned = expected.get("sales").columns().stream()
                .filter(column -> column.name().equals("amount_returned"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("sales.amount_returned is missing from the script"));
        assertEquals("amount_returned numeric(10,2) default 0 not null", returned.declaration(),
                "NOT NULL survives only next to the default that satisfies it");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute("create table sales (id integer primary key autoincrement,"
                    + " invoice_number varchar(50) not null, total_amount numeric(10,2) not null)");
            statement.execute("insert into sales (invoice_number, total_amount) values ('INV-1', 250.00)");
        }

        migrate(sqlite(file));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             ResultSet rs = connection.createStatement()
                     .executeQuery("select invoice_number, amount_returned from sales")) {
            assertTrue(rs.next(), "the pre-existing sale must survive the upgrade");
            assertEquals("INV-1", rs.getString(1));
            assertEquals(0.0, rs.getDouble(2), 0.001,
                    "existing rows take the declared default, not null");
        }
    }

    @Test
    void aSecondStartChangesNothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        DataSource dataSource = sqlite(file);
        migrate(dataSource);
        Map<String, Set<String>> afterFirst;
        Set<String> firstTables;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            firstTables = tables(connection);
            afterFirst = new java.util.TreeMap<>();
            for (String table : firstTables) {
                afterFirst.put(table, columnsOf(connection, table));
            }
        }

        migrate(dataSource);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(firstTables, tables(connection));
            for (String table : firstTables) {
                assertEquals(afterFirst.get(table), columnsOf(connection, table), table + " changed shape");
            }
        }
    }

    /**
     * The script is a checked-in snapshot of the entity model, so the model drifting away
     * from it is the failure this design introduces. Every mapped table and every explicitly
     * named column has to be in the script.
     */
    @Test
    void everyMappedEntityIsPresentInTheScript() throws Exception {
        Map<String, SqliteSchemaMigrator.Table> expected = SqliteSchemaMigrator.tables(script());
        List<String> missing = new ArrayList<>();
        for (Class<?> entity : entityClasses()) {
            String table = entity.getAnnotation(Table.class).name();
            Set<String> scriptColumns = expected.get(table) == null
                    ? Set.of()
                    : expected.get(table).columns().stream().map(SqliteSchemaMigrator.Column::name)
                            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (scriptColumns.isEmpty()) {
                missing.add("table " + table + " (" + entity.getSimpleName() + ")");
                continue;
            }
            for (java.lang.reflect.Field field : entity.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column == null || column.name().isBlank()) {
                    continue;
                }
                if (!scriptColumns.contains(column.name().toLowerCase(java.util.Locale.ROOT))) {
                    missing.add("column " + table + "." + column.name()
                            + " (" + entity.getSimpleName() + "." + field.getName() + ")");
                }
            }
        }
        assertTrue(missing.isEmpty(), "db/sqlite/schema.sql is behind the entity model: " + missing);
    }

    @Test
    void unsupportedConstraintsAreDroppedFromAnAddedColumn() {
        Map<String, SqliteSchemaMigrator.Table> tables = SqliteSchemaMigrator.tables(
                "create table if not exists expenses (amount numeric(10,2) not null,"
                        + " category varchar(50) not null check (category in ('RENT','OTHER')),"
                        + " code varchar(255) not null unique, ledger_revision BIGINT DEFAULT 0 not null,"
                        + " receipt_image LONGBLOB, primary key (id));");
        List<String> declarations = tables.get("expenses").columns().stream()
                .map(SqliteSchemaMigrator.Column::declaration)
                .toList();
        assertEquals(List.of("amount numeric(10,2)", "category varchar(50)", "code varchar(255)",
                "ledger_revision BIGINT default 0 not null", "receipt_image LONGBLOB"), declarations);
    }

    /**
     * A profit ledger as an older build created it: the event list stops before the
     * write-off, variance and gain events existed.
     */
    private static final String LEGACY_PROFIT_LEDGER = """
            create table profit_ledger (
                cogs numeric(19,4) not null,
                expense numeric(19,4) not null,
                net_revenue numeric(19,4) not null,
                quantity integer,
                tax_amount numeric(19,4) not null,
                period varchar(7) not null,
                id integer,
                occurred_at timestamp not null,
                posted_at timestamp not null,
                product_id bigint,
                source_id bigint,
                event_type varchar(30) not null check (event_type in ('SALE','RETURN','VOID')),
                source_type varchar(40) not null,
                category varchar(50),
                line_key varchar(60) not null,
                reference varchar(100),
                posting_key varchar(200) not null unique,
                primary key (id)
            )""";

    private static final String LEDGER_ROW =
            "insert into profit_ledger (cogs, expense, net_revenue, tax_amount, period, id, occurred_at,"
                    + " posted_at, event_type, source_type, line_key, posting_key) values"
                    + " (0,0,1000,0,'2026-11',";

    private static void insertLedgerRow(Statement statement, int id, String event, String postingKey)
            throws SQLException {
        statement.executeUpdate(LEDGER_ROW + id + ",'2026-11-01 08:00:00','2026-11-01 08:00:00','"
                + event + "','SALE','L" + id + "','" + postingKey + "')");
    }

    /** The chart-of-accounts mapping table before recoverable input tax was added. */
    private static final String LEGACY_GL_MAPPINGS = """
            create table gl_mappings (
                created_at timestamp not null,
                gl_account_id bigint not null,
                id integer,
                event_type varchar(50) not null check (event_type in ('CASH','ACCOUNTS_RECEIVABLE',\
            'SALES_REVENUE','SALES_DISCOUNT','SALES_TAX_PAYABLE','COST_OF_GOODS_SOLD','INVENTORY',\
            'ACCOUNTS_PAYABLE','FREIGHT_IN','OPERATING_EXPENSE','INVENTORY_REVALUATION',\
            'INVENTORY_ADJUSTMENT','EXPENSE_PAYABLE','REFUND_LIABILITY','TENDER_CLEARING',\
            'CASH_CLEARING')),
                description varchar(500),
                primary key (id)
            )""";

    private static void insertMappingRow(Statement statement, int id, String event, String description)
            throws SQLException {
        statement.executeUpdate("insert into gl_mappings (created_at, gl_account_id, id, event_type, description)"
                + " values ('2026-11-01 08:00:00', 1, " + id + ", '" + event + "', '" + description + "')");
    }

    @Test
    void freshSchemaAllowsInputTaxMappingsAndSettlements() throws Exception {
        Map<String, SqliteSchemaMigrator.Table> expected = SqliteSchemaMigrator.tables(script());

        assertTrue(SqliteSchemaMigrator.enumValues(expected.get("gl_mappings").checks().get("event_type"))
                        .contains("input_tax"),
                "the GL mapping check must accept the input-tax event the ledger journal posts");
        assertTrue(SqliteSchemaMigrator.enumValues(expected.get("profit_ledger").checks().get("settlement_account"))
                        .contains("input_tax"),
                "the profit ledger settlement check must accept input-tax reversals");
    }

    @Test
    void anUpgradedGlMappingTableAcceptsInputTax(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute(LEGACY_GL_MAPPINGS);
            insertMappingRow(statement, 1, "CASH", "Cash receipts");
            try {
                insertMappingRow(statement, 2, "INPUT_TAX", "Recoverable purchase input tax");
                throw new AssertionError("the old check list was expected to reject INPUT_TAX");
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("CHECK constraint failed"),
                        "expected a check failure, got: " + expected.getMessage());
            }
        }

        migrate(sqlite(file));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            insertMappingRow(statement, 2, "INPUT_TAX", "Recoverable purchase input tax");
            try (ResultSet rs = statement.executeQuery("select count(*) from gl_mappings")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "the old mapping plus the new input-tax mapping must be present");
            }
            try (ResultSet rs = statement.executeQuery(
                    "select description from gl_mappings where id = 1")) {
                assertTrue(rs.next());
                assertEquals("Cash receipts", rs.getString(1), "the existing mapping must survive the rebuild");
            }
        }
    }

    @Test
    void anUpgradedTableAcceptsAnEventTheOldCheckListDidNotAllow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute(LEGACY_PROFIT_LEDGER);
            insertLedgerRow(statement, 1, "SALE", "PK-1");
            insertLedgerRow(statement, 2, "VOID", "PK-2");
            try {
                insertLedgerRow(statement, 3, "INVENTORY_GAIN", "PK-3");
                throw new AssertionError("the old check list was expected to reject INVENTORY_GAIN");
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("CHECK constraint failed"),
                        "expected a check failure, got: " + expected.getMessage());
            }
        }

        migrate(sqlite(file));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            insertLedgerRow(statement, 3, "INVENTORY_GAIN", "PK-3");
            try (ResultSet rs = statement.executeQuery("select count(*) from profit_ledger")) {
                rs.next();
                assertEquals(3, rs.getInt(1), "both posted rows plus the new gain must be present");
            }
            try (ResultSet rs = statement.executeQuery(
                    "select event_type from profit_ledger where id in (1,2) order by id")) {
                assertTrue(rs.next());
                assertEquals("SALE", rs.getString(1));
                assertTrue(rs.next());
                assertEquals("VOID", rs.getString(1), "existing entries must survive the rebuild");
            }
        }
    }

    @Test
    void aRebuildBacksTheDatabaseUpOnceAndTheNextStartChangesNothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute(LEGACY_PROFIT_LEDGER);
            insertLedgerRow(statement, 1, "SALE", "PK-1");
        }

        migrate(sqlite(file));

        List<Path> backups;
        try (var stream = Files.list(dir)) {
            backups = stream.filter(path -> path.getFileName().toString().contains(".pre-upgrade-")).toList();
        }
        assertEquals(1, backups.size(), "one backup per start that rebuilds");
        assertTrue(backups.get(0).getFileName().toString().startsWith("lumipos.db.pre-upgrade-"));
        assertTrue(backups.get(0).getFileName().toString().endsWith(".bak"));

        migrate(sqlite(file));

        try (var stream = Files.list(dir)) {
            assertEquals(1, stream.filter(path -> path.getFileName().toString().contains(".pre-upgrade-")).count(),
                    "a start that rebuilds nothing must not make another backup");
        }
    }

    /** Copies an upgrade made on an earlier day, named the way the migrator names them. */
    private static Path writeUpgradeBackup(Path dir, String stamp) throws IOException {
        return Files.writeString(dir.resolve("lumipos.db.pre-upgrade-" + stamp + ".bak"),
                "a database from " + stamp);
    }

    /** The same, aged to {@code minutes} past the epoch so the clock orders it, not the name. */
    private static Path writeUpgradeBackup(Path dir, String stamp, long minutes) throws IOException {
        Path copy = writeUpgradeBackup(dir, stamp);
        Files.setLastModifiedTime(copy, FileTime.from(Instant.EPOCH.plusSeconds(minutes * 60L)));
        return copy;
    }

    private static List<String> upgradeBackups(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("lumipos.db.pre-upgrade-") && name.endsWith(".bak"))
                    .sorted().toList();
        }
    }

    /** Opens the ledger the enum-check rebuild is triggered by, with one posted row. */
    private static void writeLegacyLedger(Path file) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute(LEGACY_PROFIT_LEDGER);
            insertLedgerRow(statement, 1, "SALE", "PK-1");
        }
    }

    @Test
    void aStartKeepsOnlyTheNewestUpgradeBackups(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        writeLegacyLedger(file);
        for (String stamp : List.of("202601011200", "202601021200", "202601031200",
                "202601041200", "202601051200")) {
            writeUpgradeBackup(dir, stamp);
        }
        Path restoreCopy = Files.writeString(dir.resolve("lumipos.db.pre-restore-20260101120000"),
                "another feature's file");

        migrate(sqlite(file));

        List<String> kept = upgradeBackups(dir);
        assertEquals(3, kept.size(), "three copies is the default the setting does not override");
        assertTrue(kept.contains("lumipos.db.pre-upgrade-202601051200.bak"),
                "the newest copy from before this start must survive: " + kept);
        assertTrue(kept.contains("lumipos.db.pre-upgrade-202601041200.bak"),
                "the second newest must survive too: " + kept);
        assertFalse(kept.contains("lumipos.db.pre-upgrade-202601031200.bak"),
                "the third newest is the oldest one allowed to go: " + kept);
        assertTrue(kept.get(kept.size() - 1).compareTo("lumipos.db.pre-upgrade-202601051200.bak") > 0,
                "the copy made by this start must be the one that is still newest: " + kept);
        assertTrue(Files.exists(restoreCopy), "the prune must not touch another feature's files");
    }

    @Test
    void theNumberOfKeptUpgradeBackupsComesFromTheSetting(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        writeLegacyLedger(file);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute("create table system_settings (is_public boolean not null,"
                    + " created_at timestamp not null, id integer, updated_at timestamp,"
                    + " description varchar(1000), setting_value varchar(5000), data_type varchar(255) not null,"
                    + " setting_key varchar(255) not null unique, primary key (id))");
            statement.executeUpdate("insert into system_settings (is_public, created_at, id, setting_value,"
                    + " data_type, setting_key) values (0,'2026-01-01 00:00:00',1,'1','INTEGER',"
                    + "'sqlite.upgradeBackup.keep')");
        }
        writeUpgradeBackup(dir, "202601011200");
        writeUpgradeBackup(dir, "202601021200");

        migrate(sqlite(file));

        List<String> kept = upgradeBackups(dir);
        assertEquals(1, kept.size(), "one kept copy means the one made by this start");
        assertTrue(kept.get(0).compareTo("lumipos.db.pre-upgrade-202601021200.bak") > 0,
                "the shop's own copy is the one that stays: " + kept);
    }

    @Test
    void aSameMinuteCopyIsKeptByItsTimeAndNotByItsName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute("create table system_settings (setting_key varchar(255) not null unique,"
                    + " setting_value varchar(5000), data_type varchar(255))");
            statement.executeUpdate("insert into system_settings (setting_key, setting_value, data_type)"
                    + " values ('sqlite.upgradeBackup.keep', '2', 'INTEGER')");
        }

        // The oldest minute holds two copies: the second is named ...-2.bak, and '-'
        // sorts before '.', so "-2.bak" comes BEFORE the ".bak" it was made after. A
        // name order therefore calls the newest copy the oldest one there is.
        writeUpgradeBackup(dir, "202601011200", 1);
        writeUpgradeBackup(dir, "202601011200-2", 4);
        writeUpgradeBackup(dir, "202601021200", 2);
        writeUpgradeBackup(dir, "202601031200", 3);

        DataSource dataSource = sqlite(file);
        try (Connection connection = dataSource.getConnection()) {
            new SqliteSchemaMigrator(dataSource, new DefaultResourceLoader())
                    .pruneOldUpgradeBackups(connection, null);
        }

        List<String> kept = upgradeBackups(dir);
        assertEquals(List.of("lumipos.db.pre-upgrade-202601011200-2.bak",
                        "lumipos.db.pre-upgrade-202601031200.bak"), kept,
                "the two newest by the clock on the file are the two that stay");
    }

    @Test
    void aMigrationThatFailedDeletesNoBackups(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute(LEGACY_PROFIT_LEDGER);
            insertLedgerRow(statement, 1, "SALE", "PK-1");
            // The name the rebuild has to create its replacement table under is taken, so the
            // start gives up after it has already written this start's backup.
            statement.execute("create table profit_ledger__new (id integer)");
        }
        List<String> fromEarlierStarts = List.of("lumipos.db.pre-upgrade-202601011200.bak",
                "lumipos.db.pre-upgrade-202601021200.bak", "lumipos.db.pre-upgrade-202601031200.bak",
                "lumipos.db.pre-upgrade-202601041200.bak", "lumipos.db.pre-upgrade-202601051200.bak");
        for (String name : fromEarlierStarts) {
            Files.writeString(dir.resolve(name), "a database worth going back to");
        }

        SQLException thrown = assertThrows(SQLException.class, () -> migrate(sqlite(file)));
        assertTrue(thrown.getMessage().contains("profit_ledger"),
                "the failure should name the table it could not rebuild: " + thrown.getMessage());

        List<String> kept = upgradeBackups(dir);
        assertTrue(kept.containsAll(fromEarlierStarts),
                "a start that did not finish must leave every backup alone: " + kept);
    }

    @Test
    void aTableAnotherTablePointsAtKeepsItsRowsAfterARebuild(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute("create table shop_info (id integer, shop_name varchar(255) not null,"
                    + " shop_type varchar(50) not null check (shop_type in ('MINI_MART','GROCERY')),"
                    + " discount_type varchar(20) check (discount_type in ('PERCENTAGE','AMOUNT')),"
                    + " primary key (id))");
            statement.execute("create table shop_counters (id integer primary key, shop_id bigint"
                    + " references shop_info (id), label varchar(50))");
            statement.executeUpdate("insert into shop_info (id, shop_name, shop_type, discount_type)"
                    + " values (7,'Mini Mart','GROCERY','AMOUNT')");
            statement.executeUpdate("insert into shop_counters (id, shop_id, label) values (1,7,'Front')");
        }

        migrate(sqlite(file));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("select shop_name, shop_type from shop_info")) {
                assertTrue(rs.next(), "the rebuilt table must still hold the shop row");
                assertEquals("Mini Mart", rs.getString(1));
                assertEquals("GROCERY", rs.getString(2));
            }
            try (ResultSet rs = statement.executeQuery("select label from shop_counters")) {
                assertTrue(rs.next(), "the referencing table must be untouched");
                assertEquals("Front", rs.getString(1));
            }
            try (ResultSet rs = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(rs.next(), "no row may be left pointing at a missing parent");
            }
            // The point of the rebuild: a shop type the old list refused is accepted now.
            statement.executeUpdate("update shop_info set shop_type = 'PHARMACY' where id = 7");
        }
    }

    @Test
    void aRebuildThatCannotStartLeavesTheOriginalTableAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute(LEGACY_PROFIT_LEDGER);
            insertLedgerRow(statement, 1, "SALE", "PK-1");
            insertLedgerRow(statement, 2, "VOID", "PK-2");
            // The name the swap needs is taken, so nothing can be built without risking
            // the rows that are already posted.
            statement.execute("create table profit_ledger__new (id integer)");
        }

        SQLException failure = assertThrows(SQLException.class, () -> migrate(sqlite(file)));
        assertTrue(failure.getMessage().contains("profit_ledger"),
                "the message must name the table: " + failure.getMessage());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("select count(*) from profit_ledger")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "the shop's own rows must still be there");
            }
            try (ResultSet rs = statement.executeQuery(
                    "select sql from sqlite_master where type='table' and name='profit_ledger'")) {
                rs.next();
                assertTrue(rs.getString(1).contains("'SALE','RETURN','VOID'"),
                        "the original definition must be intact, not half rebuilt");
            }
        }
    }

    /**
     * The rebuild widens value lists and changes nothing else, so a shop keeps the columns
     * its own data has even where the current model no longer mentions them.
     */
    @Test
    void aRebuildKeepsAColumnTheCurrentModelNoLongerDeclares(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lumipos.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            statement.execute(LEGACY_PROFIT_LEDGER.replace("primary key (id)",
                    "legacy_note varchar(40), primary key (id)"));
            insertLedgerRow(statement, 1, "SALE", "PK-1");
            statement.executeUpdate("update profit_ledger set legacy_note = 'from the old build' where id = 1");
        }

        migrate(sqlite(file));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "select legacy_note from profit_ledger where id = 1")) {
                assertTrue(rs.next());
                assertEquals("from the old build", rs.getString(1));
            }
            insertLedgerRow(statement, 2, "REVALUATION", "PK-2");
        }
    }

    /** The dependsOn hook names this bean literally, so a rename has to be caught here. */
    @Test
    void theMigratorBeanKeepsTheNameTheEntityManagerFactoryWaitsFor() {
        assertEquals("sqliteSchemaMigrator",
                Character.toLowerCase(SqliteSchemaMigrator.class.getSimpleName().charAt(0))
                        + SqliteSchemaMigrator.class.getSimpleName().substring(1));
    }

    private static List<Class<?>> entityClasses() throws ClassNotFoundException, IOException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.bms.entity")) {
            classes.add(Class.forName(definition.getBeanClassName()));
        }
        assertTrue(classes.size() > 40, "the entity scan found " + classes.size() + " classes");
        return classes;
    }
}
