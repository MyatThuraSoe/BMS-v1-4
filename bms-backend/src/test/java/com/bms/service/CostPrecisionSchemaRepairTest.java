package com.bms.service;

import com.bms.config.CostPrecisionSchemaRepair;
import com.bms.entity.CountSheet;
import com.bms.entity.CountSheetItem;
import com.bms.entity.OrderItem;
import com.bms.entity.Product;
import com.bms.entity.ProductPriceHistory;
import com.bms.entity.PurchaseItem;
import com.bms.entity.SaleItem;
import com.bms.entity.StockMovement;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostPrecisionSchemaRepairTest {

    private static final BigDecimal DEFAULT_VALUE = new BigDecimal("7.25");
    private static final BigDecimal SIX_DECIMAL_COST = new BigDecimal("0.337835");
    private static final List<Target> TARGETS = List.of(
            new Target("products", "cost_price", Product.class, "costPrice", true),
            new Target("sale_items", "cost_price_at_sale", SaleItem.class, "costPriceAtSale", true),
            new Target("stock_movements", "valuation_amount", StockMovement.class, "valuationAmount", true),
            new Target("product_price_history", "old_price", ProductPriceHistory.class, "oldPrice", false),
            new Target("product_price_history", "new_price", ProductPriceHistory.class, "newPrice", false),
            new Target("product_price_history", "purchase_unit_price", ProductPriceHistory.class,
                    "purchaseUnitPrice", true),
            new Target("product_price_history", "inventory_value_delta", ProductPriceHistory.class,
                    "inventoryValueDelta", true),
            new Target("purchase_items", "unit_cost", PurchaseItem.class, "unitCost", false),
            new Target("purchase_items", "landed_unit_cost", PurchaseItem.class, "landedUnitCost", true),
            new Target("purchase_items", "base_unit_cost", PurchaseItem.class, "baseUnitCost", true),
            new Target("order_items", "cost_price_at_order", OrderItem.class, "costPriceAtOrder", true),
            new Target("count_sheet_items", "valuation_amount", CountSheetItem.class, "valuationAmount", true),
            new Target("count_sheets", "variance_value", CountSheet.class, "varianceValue", true)
    );

    private Connection keepAliveConnection;
    private JdbcTemplate jdbc;
    private ApplicationRunner repair;

    @BeforeEach
    void openIsolatedDatabase() throws SQLException {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:cost_precision_" + UUID.randomUUID(), "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        keepAliveConnection = dataSource.getConnection();
        jdbc = new JdbcTemplate(dataSource);
        repair = new CostPrecisionSchemaRepair(dataSource);
    }

    @AfterEach
    void closeIsolatedDatabase() throws SQLException {
        if (keepAliveConnection != null) {
            keepAliveConnection.close();
        }
    }

    @ParameterizedTest(name = "C7 repairs legacy DECIMAL({0},{1}) without data loss")
    @CsvSource({"10, 2", "19, 4"})
    void widensEveryCostColumnPreservingDataConstraintsAndIdempotence(int precision, int scale) throws Exception {
        createLegacySchema(precision, scale);
        BigDecimal positive = new BigDecimal(scale == 2 ? "12345678.90" : "123456789012345.6789");
        BigDecimal negative = new BigDecimal(scale == 2 ? "-12.34" : "-12.3456");
        seedLegacyRows(positive, negative);

        repair.run(new DefaultApplicationArguments());

        for (String table : tables()) {
            jdbc.update("INSERT INTO " + table + " (id) VALUES (5)");
        }
        Map<Target, Metadata> firstMetadata = new LinkedHashMap<>();
        Map<Target, List<BigDecimal>> firstValues = new LinkedHashMap<>();
        for (Target target : TARGETS) {
            jdbc.update("UPDATE " + target.table() + " SET " + target.column() + " = ? WHERE id = 5",
                    SIX_DECIMAL_COST);
            firstMetadata.put(target, metadata(target));
            firstValues.put(target, values(target));
        }

        repair.run(new DefaultApplicationArguments());

        assertAll("Every stored cost target", TARGETS.stream().map(target -> () -> {
            List<BigDecimal> actual = firstValues.get(target);
            assertAll(target.label(),
                    () -> assertRepairedMetadata(target, firstMetadata.get(target)),
                    () -> assertAll("Values after the first repair",
                            () -> assertEquals(5, actual.size(), "row count"),
                            () -> assertDecimal(positive, actual.get(0), "positive legacy value"),
                            () -> assertDecimal(negative, actual.get(1), "negative legacy value"),
                            () -> assertDecimal(target.nullable() ? null : BigDecimal.ZERO,
                                    actual.get(2), "legacy null or zero"),
                            () -> assertDecimal(DEFAULT_VALUE, actual.get(3), "legacy default value"),
                            () -> assertDecimal(SIX_DECIMAL_COST, actual.get(4), "six-decimal round trip")),
                    () -> assertEquals(firstMetadata.get(target), metadata(target),
                            "second run must not change column metadata"),
                    () -> assertEquals(actual, values(target), "second run must not change stored values"),
                    () -> assertDecimal(DEFAULT_VALUE, jdbc.queryForObject(
                            "SELECT " + target.column() + " FROM " + target.table() + " WHERE id = 4",
                            BigDecimal.class), "default after the second run"),
                    () -> {
                        if (!target.nullable()) {
                            assertThrows(DataIntegrityViolationException.class,
                                    () -> jdbc.update("UPDATE " + target.table() + " SET " + target.column()
                                            + " = NULL WHERE id = 5"),
                                    "NOT NULL must remain enforced");
                        }
                    });
        }));
    }

    @Test
    void entityCostColumnsDeclarePrecision19AndScale6() {
        assertAll("C7 entity mappings", TARGETS.stream().map(target -> () -> {
            Column column = target.entity().getDeclaredField(target.field()).getAnnotation(Column.class);
            assertNotNull(column, target.label() + " must have @Column");
            assertAll(target.label(),
                    () -> assertEquals(target.table(), target.entity().getAnnotation(Table.class).name()),
                    () -> assertEquals(target.column(), column.name()),
                    () -> assertEquals(19, column.precision(), "precision"),
                    () -> assertEquals(6, column.scale(), "scale"));
        }));
    }

    @Test
    void mysqlWideningKeepsNotNullAndNumericDefault() throws Exception {
        javax.sql.DataSource dataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        Connection connection = org.mockito.Mockito.mock(Connection.class);
        java.sql.DatabaseMetaData database = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        JdbcTemplate template = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(dataSource.getConnection()).thenReturn(connection);
        org.mockito.Mockito.when(connection.getMetaData()).thenReturn(database);
        org.mockito.Mockito.when(database.getURL()).thenReturn("jdbc:mysql://localhost/isolated_test");
        org.mockito.Mockito.when(template.getDataSource()).thenReturn(dataSource);
        org.mockito.Mockito.when(template.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    boolean legacy = "old_price".equals(invocation.getArgument(3));
                    java.sql.ResultSet row = org.mockito.Mockito.mock(java.sql.ResultSet.class);
                    org.mockito.Mockito.when(row.getInt("NUMERIC_PRECISION")).thenReturn(legacy ? 10 : 19);
                    org.mockito.Mockito.when(row.getInt("NUMERIC_SCALE")).thenReturn(legacy ? 2 : 6);
                    org.mockito.Mockito.when(row.getString("IS_NULLABLE")).thenReturn("NO");
                    org.mockito.Mockito.when(row.getString("COLUMN_DEFAULT")).thenReturn("7.25");
                    org.springframework.jdbc.core.RowMapper<?> mapper = invocation.getArgument(1);
                    return mapper.mapRow(row, 0);
                });
        CostPrecisionSchemaRepair mysqlRepair = new CostPrecisionSchemaRepair(dataSource);
        org.springframework.test.util.ReflectionTestUtils.setField(mysqlRepair, "jdbcTemplate", template);

        mysqlRepair.run(new DefaultApplicationArguments());

        org.mockito.Mockito.verify(template).execute("ALTER TABLE product_price_history MODIFY COLUMN old_price "
                + "DECIMAL(19,6) NOT NULL DEFAULT 7.25");
    }

    @Test
    void missingRequiredTablePropagatesRepairFailure() {
        createLegacySchema(10, 2);
        jdbc.execute("DROP TABLE sale_items");

        assertThrows(Exception.class, () -> repair.run(new DefaultApplicationArguments()),
                "A missing cost table must fail startup instead of silently retaining legacy precision");
    }

    private void createLegacySchema(int precision, int scale) {
        for (String table : tables()) {
            String columns = TARGETS.stream()
                    .filter(target -> target.table().equals(table))
                    .map(target -> target.column() + " DECIMAL(" + precision + "," + scale + ") DEFAULT "
                            + DEFAULT_VALUE.toPlainString() + (target.nullable() ? "" : " NOT NULL"))
                    .collect(Collectors.joining(", "));
            jdbc.execute("CREATE TABLE " + table + " (id INTEGER PRIMARY KEY, " + columns + ")");
        }
    }

    private void seedLegacyRows(BigDecimal positive, BigDecimal negative) {
        for (String table : tables()) {
            for (int id = 1; id <= 4; id++) {
                jdbc.update("INSERT INTO " + table + " (id) VALUES (?)", id);
            }
        }
        for (Target target : TARGETS) {
            String update = "UPDATE " + target.table() + " SET " + target.column() + " = ? WHERE id = ?";
            jdbc.update(update, positive, 1);
            jdbc.update(update, negative, 2);
            jdbc.update(update, target.nullable() ? null : BigDecimal.ZERO, 3);
        }
    }

    private List<String> tables() {
        return TARGETS.stream().map(Target::table).distinct().toList();
    }

    private Metadata metadata(Target target) {
        return jdbc.queryForObject(
                "SELECT NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE, COLUMN_DEFAULT "
                        + "FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                (rs, rowNum) -> new Metadata(rs.getInt("NUMERIC_PRECISION"), rs.getInt("NUMERIC_SCALE"),
                        rs.getString("IS_NULLABLE"), rs.getString("COLUMN_DEFAULT")),
                target.table().toUpperCase(Locale.ROOT), target.column().toUpperCase(Locale.ROOT));
    }

    private List<BigDecimal> values(Target target) {
        return jdbc.query("SELECT " + target.column() + " FROM " + target.table() + " ORDER BY id",
                (rs, rowNum) -> rs.getBigDecimal(1));
    }

    private void assertRepairedMetadata(Target target, Metadata actual) {
        assertAll("Column metadata",
                () -> assertTrue(actual.precision() >= 19, "precision must be >= 19, was " + actual.precision()),
                () -> assertTrue(actual.scale() >= 6, "scale must be >= 6, was " + actual.scale()),
                () -> assertEquals(target.nullable() ? "YES" : "NO", actual.nullability(), "nullability"),
                () -> {
                    assertNotNull(actual.defaultExpression(), "numeric default must remain declared");
                    assertDecimal(DEFAULT_VALUE,
                            jdbc.queryForObject("SELECT " + actual.defaultExpression(), BigDecimal.class),
                            "numeric default metadata");
                });
    }

    private void assertDecimal(BigDecimal expected, BigDecimal actual, String message) {
        if (expected == null) {
            assertNull(actual, message);
        } else {
            assertNotNull(actual, message);
            assertTrue(expected.compareTo(actual) == 0,
                    () -> message + ": expected " + expected.toPlainString() + " but was " + actual.toPlainString());
        }
    }

    private record Target(String table, String column, Class<?> entity, String field, boolean nullable) {
        String label() {
            return table + "." + column;
        }
    }

    private record Metadata(int precision, int scale, String nullability, String defaultExpression) {
    }
}
