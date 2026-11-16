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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
