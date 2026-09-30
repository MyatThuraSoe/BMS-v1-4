package com.bms.sqlite;

import com.bms.entity.Sale;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards a failure mode that Hibernate reports only as a WARN in the log and
 * that therefore ships silently.
 *
 * <p>With {@code ddl-auto=update} a column that exists in the entity but not in
 * an existing shop database is added via {@code ALTER TABLE ... ADD COLUMN}.
 * SQLite refuses that when the column is NOT NULL and has no DEFAULT. Hibernate
 * logs the rejection as a warning, starts up anyway, and the column stays
 * missing - so every subsequent query of the sales table fails for that shop
 * until the database is deleted by hand.
 *
 * <p>These tests replay that exact sequence against real SQLite, using the
 * column definition declared on the entity, so the guarantee is checked against
 * the DDL Hibernate will really emit rather than a hand-written copy that can
 * drift away from it.
 */
class SqliteNotNullColumnMigrationTest {

    /**
     * The {@code sales} table as it exists on a shop that predates
     * {@code amount_returned}. Nullable extras are included because ddl-auto
     * also ALTERs those, and they must keep working.
     */
    private static final String LEGACY_SALES = """
            create table sales (
                id integer primary key autoincrement,
                invoice_number varchar(50) not null,
                total_amount numeric(10,2) not null,
                amount_paid numeric(10,2) not null,
                change_given numeric(10,2) not null,
                is_voided boolean,
                notes varchar(500)
            )""";

    private static Connection open(Path dir) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("legacy.db"));
    }

    private static Column columnOf(String fieldName) throws NoSuchFieldException {
        Field field = Sale.class.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column, fieldName + " must be annotated with @Column");
        return column;
    }

    private static boolean hasColumn(Connection connection, String column) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery("PRAGMA table_info(sales)")) {
            while (rs.next()) {
                if (column.equals(rs.getString(2))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void notNullAmountReturnedColumnCanBeAddedToLegacyDatabase(@TempDir Path dir) throws Exception {
        Column column = columnOf("amountReturned");
        assertTrue(column.nullable() == false, "amountReturned is intentionally NOT NULL");

        try (Connection connection = open(dir);
             Statement statement = connection.createStatement()) {

            statement.execute(LEGACY_SALES);
            statement.executeUpdate("insert into sales (invoice_number, total_amount, amount_paid, change_given)"
                    + " values ('INV-1', 100.00, 100.00, 0.00)");

            // The DDL Hibernate generates for a missing column, built from the
            // column definition declared on the entity.
            String ddl = "alter table sales add column "
                    + column.name() + " " + column.columnDefinition();

            try {
                statement.executeUpdate(ddl);
            } catch (SQLException e) {
                fail("SQLite rejected the upgrade DDL (" + ddl + "): " + e.getMessage()
                        + ". A NOT NULL column added by ddl-auto must declare a DEFAULT.");
            }

            assertTrue(hasColumn(connection, "amount_returned"),
                    "amount_returned must exist after the migration");

            // Existing sales must survive the upgrade rather than erroring on a
            // null amount_returned.
            List<String> invoices = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(
                    "select invoice_number, amount_returned from sales")) {
                while (rs.next()) {
                    invoices.add(rs.getString(1) + "=" + rs.getString(2));
                }
            }
            assertEquals(List.of("INV-1=0"), invoices,
                    "the pre-existing sale must backfill to a zero returned amount");
        }
    }

    @Test
    void notNullColumnsAbsentFromLegacyDatabaseAreAlterableBySqlite(@TempDir Path dir) throws Exception {
        // Any NOT NULL column without a DEFAULT will be rejected by SQLite the
        // first time a shop upgrades past the version that introduced it, so
        // assert the invariant across the whole entity rather than only for
        // the column that already broke. Columns that the legacy table already
        // has are skipped: those were created by CREATE TABLE, never ALTERed,
        // so they are not at risk.
        for (Field field : Sale.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column == null || column.nullable() || column.columnDefinition().isBlank()) {
                continue;
            }
            try (Connection connection = open(dir);
                 Statement statement = connection.createStatement()) {
                statement.execute(LEGACY_SALES);
                if (hasColumn(connection, column.name())) {
                    continue;
                }
                statement.executeUpdate("alter table sales add column " + column.name() + " "
                        + column.columnDefinition());
            } catch (SQLException e) {
                fail("NOT NULL column '" + column.name() + "' (" + field.getName()
                        + ") cannot be added to an existing SQLite database. Give it a DEFAULT "
                        + "in its columnDefinition. SQLite said: " + e.getMessage());
            }
        }
    }
}
