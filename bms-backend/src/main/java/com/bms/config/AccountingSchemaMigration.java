package com.bms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Idempotent migration for the accounting schema introduced by the business
 * logic fixes (P1/O8 ledgers, period close, O1 customer credit, O2 order
 * discount). Follows the project's established runner-based migration
 * mechanism (see {@link CostPrecisionSchemaRepair}); Flyway is on the
 * classpath but disabled in every profile.
 *
 * <p>Runs on H2, SQLite and MySQL. Every statement is safe to re-run: tables
 * and indexes are created only when absent, columns are added only when
 * missing, and new money columns are backfilled to zero for existing rows.
 * Hibernate ddl-auto may also create these objects; this runner guarantees
 * they exist with the correct types and uniqueness guards regardless.
 */
@Component
@Order(1)
public class AccountingSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountingSchemaMigration.class);

    private final JdbcTemplate jdbcTemplate;

    public AccountingSchemaMigration(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    private enum Dialect { H2, SQLITE, MYSQL }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        Dialect dialect = detectDialect();
        createLedgerTables(dialect);
        createPurchasePayments(dialect);
        createLedgerIndexes(dialect);
        ensureColumn(dialect, "customers", "credit_balance", "DECIMAL(19,2)", "0");
        ensureColumn(dialect, "orders", "discount_amount", "DECIMAL(10,2)", "0");
        ensureColumn(dialect, "expenses", "capitalized",
                dialect == Dialect.SQLITE ? "INTEGER" : "BOOLEAN", "0");
        ensureNullableColumn(dialect, "expenses", "purchase_expense_id", "BIGINT");
        ensureNullableColumn(dialect, "count_sheets", "client_submitted_at", "TIMESTAMP");
        ensureNullableColumn(dialect, "count_sheets", "sync_received_at", "TIMESTAMP");
        ensureNullableColumn(dialect, "count_sheet_items", "live_quantity_at_apply", "INT");
        ensureNullableColumn(dialect, "count_sheet_items", "variance_at_apply", "INT");
        ensureNullableColumn(dialect, "count_sheet_items", "client_system_quantity", "INT");
        ensureNullableColumn(dialect, "sale_returns", "debt_reduction_amount", "DECIMAL(10,2)");
        ensureNullableColumn(dialect, "sale_returns", "refund_amount", "DECIMAL(10,2)");
        ensureNullableColumn(dialect, "ar_payments", "reversed_at", "TIMESTAMP");
        ensureNullableColumn(dialect, "ar_payments", "reversal_note", "VARCHAR(1000)");
        ensureNullableColumn(dialect, "stock_movements", "source_sale_id", "BIGINT");
        ensureNullableColumn(dialect, "stock_movements", "source_return_id", "BIGINT");
        ensureNullableColumn(dialect, "profit_ledger", "settlement_cash", "DECIMAL(19,4)");
        ensureNullableColumn(dialect, "profit_ledger", "settlement_clearing", "DECIMAL(19,4)");
        ensureNullableColumn(dialect, "profit_ledger", "settlement_ar", "DECIMAL(19,4)");
        ensureNullableColumn(dialect, "profit_ledger", "settlement_refund_liability", "DECIMAL(19,4)");
        ensureNullableColumn(dialect, "expenses", "updated_by", "BIGINT");
        ensureNullableColumn(dialect, "expenses", "updated_at", "TIMESTAMP");
        ensureNullableColumn(dialect, "stock_movements", "source_transfer_id", "BIGINT");
        ensureNullableColumn(dialect, "backup_settings", "scheduled_time", "TIME");
        ensureColumn(dialect, "backup_settings", "retention_count", "INTEGER", "30");
        migrateUserIdentity();
        ensureUniqueIndex(dialect, "uq_count_sheets_sync_key", "count_sheets", "sync_key");
    }

    /**
     * N2: the whole user-identity upgrade step, safe to run on every boot and in
     * any order relative to the seeders. The columns are added first (nullable,
     * so an existing table with rows accepts them), then filled and deduplicated,
     * and only then are the unique indexes created.
     */
    public void migrateUserIdentity() {
        Dialect dialect = detectDialect();
        ensureNullableColumn(dialect, "users", "token_version", "INTEGER");
        ensureNullableColumn(dialect, "users", "username_normalized", "VARCHAR(255)");
        ensureNullableColumn(dialect, "users", "email_normalized", "VARCHAR(255)");
        normalizeAndDedupeUserAccounts();
        ensureUniqueIndex(dialect, "uq_users_username_normalized", "users", "username_normalized");
        ensureUniqueIndex(dialect, "uq_users_email_normalized", "users", "email_normalized");
    }

    private Dialect detectDialect() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String url = connection.getMetaData().getURL().toLowerCase(Locale.ROOT);
            if (url.contains(":sqlite:")) return Dialect.SQLITE;
            if (url.contains(":h2:")) return Dialect.H2;
            if (url.contains(":mysql:")) return Dialect.MYSQL;
            throw new IllegalStateException("Unsupported database for accounting schema migration");
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the database for accounting schema migration", ex);
        }
    }

    /**
     * N2: databases that predate the normalized identity columns leave them null,
     * so they must be filled before the unique indexes can exist. Case-mismatched
     * duplicates ({@code admin} next to {@code Admin}) are released with a per-id
     * suffix and deactivated instead of deleted, so an upgrade never loses a row
     * and never aborts on the index. Safe to repeat: after the first run there is
     * nothing left to fill or release.
     *
     * @return the number of duplicate keys released
     */
    public int normalizeAndDedupeUserAccounts() {
        int tokenVersions = jdbcTemplate.update(
                "UPDATE users SET token_version = 0 WHERE token_version IS NULL");
        int usernames = jdbcTemplate.update(
                "UPDATE users SET username_normalized = LOWER(TRIM(username)) WHERE username_normalized IS NULL");
        int emails = jdbcTemplate.update(
                "UPDATE users SET email_normalized = LOWER(TRIM(email)) WHERE email_normalized IS NULL AND email IS NOT NULL");
        int released = releaseDuplicateUserKeys("username_normalized", true)
                + releaseDuplicateUserKeys("email_normalized", false);
        if (tokenVersions + usernames + emails + released > 0) {
            log.info("User identity backfill: {} token versions, {} usernames, {} emails filled,"
                    + " {} duplicate keys released", tokenVersions, usernames, emails, released);
        }
        return released;
    }

    private int releaseDuplicateUserKeys(String column, boolean deactivate) {
        List<String> sharedKeys = jdbcTemplate.queryForList("SELECT " + column + " FROM users WHERE " + column
                + " IS NOT NULL GROUP BY " + column + " HAVING COUNT(*) > 1", String.class);
        int released = 0;
        for (String sharedKey : sharedKeys) {
            List<Long> ownerIds = jdbcTemplate.queryForList("SELECT id FROM users WHERE " + column
                    + " = ? ORDER BY id", Long.class, sharedKey);
            // The lowest id keeps the key; every later account is released.
            for (Long duplicateId : ownerIds.subList(1, ownerIds.size())) {
                String releasedKey = duplicateUserKey(sharedKey, duplicateId);
                if (deactivate) {
                    jdbcTemplate.update("UPDATE users SET " + column + " = ?, is_active = ? WHERE id = ?",
                            releasedKey, false, duplicateId);
                } else {
                    jdbcTemplate.update("UPDATE users SET " + column + " = ? WHERE id = ?", releasedKey, duplicateId);
                }
                jdbcTemplate.update("INSERT INTO audit_logs (user_id, action, description, entity_type, entity_id,"
                                + " old_values, new_values, ip_address, user_agent, timestamp)"
                                + " VALUES (?, 'USER_DUPLICATE_DETECTED', ?, 'User', ?, ?, ?, NULL, NULL, ?)",
                        duplicateId, "Duplicate account for " + column + " '" + sharedKey + "': kept user "
                                + ownerIds.get(0) + ", released this account as '" + releasedKey + "'",
                        duplicateId, sharedKey, releasedKey, LocalDateTime.now());
                released++;
            }
        }
        return released;
    }

    private static String duplicateUserKey(String key, Long duplicateId) {
        String suffix = "#dup_" + duplicateId;
        int keep = 255 - suffix.length();
        return (key.length() > keep ? key.substring(0, keep) : key) + suffix;
    }

    private void createLedgerTables(Dialect dialect) {
        String id = switch (dialect) {
            case H2 -> "id BIGINT GENERATED BY DEFAULT AS IDENTITY";
            case MYSQL -> "id BIGINT NOT NULL AUTO_INCREMENT";
            case SQLITE -> "id INTEGER PRIMARY KEY AUTOINCREMENT";
        };
        String tablePk = dialect == Dialect.SQLITE ? "" : ", PRIMARY KEY (id)";

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS inventory_ledger ("
                + id
                + ", product_id BIGINT NOT NULL"
                + ", location_id BIGINT"
                + ", event_type VARCHAR(30) NOT NULL"
                + ", source_type VARCHAR(40) NOT NULL"
                + ", source_id BIGINT"
                + ", line_key VARCHAR(60) NOT NULL DEFAULT ''"
                + ", qty_delta INT NOT NULL"
                + ", unit_cost DECIMAL(19,6) NOT NULL"
                + ", value_delta DECIMAL(19,6) NOT NULL"
                + ", qty_after INT NOT NULL"
                + ", value_after DECIMAL(19,6) NOT NULL"
                + ", unit_cost_after DECIMAL(19,6) NOT NULL"
                + ", reason VARCHAR(1000)"
                + ", created_by BIGINT"
                + ", occurred_at TIMESTAMP NOT NULL"
                + ", posted_at TIMESTAMP NOT NULL"
                + tablePk
                + ", CONSTRAINT uq_inventory_ledger_source UNIQUE (source_type, source_id, event_type, product_id, line_key)"
                + ")");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS profit_ledger ("
                + id
                + ", period VARCHAR(7) NOT NULL"
                + ", event_type VARCHAR(30) NOT NULL"
                + ", source_type VARCHAR(40) NOT NULL"
                + ", source_id BIGINT"
                + ", product_id BIGINT"
                + ", line_key VARCHAR(60) NOT NULL DEFAULT ''"
                + ", quantity INT"
                + ", category VARCHAR(50)"
                + ", posting_key VARCHAR(200) NOT NULL"
                + ", net_revenue DECIMAL(19,4) NOT NULL DEFAULT 0"
                + ", cogs DECIMAL(19,4) NOT NULL DEFAULT 0"
                + ", expense DECIMAL(19,4) NOT NULL DEFAULT 0"
                + ", tax_amount DECIMAL(19,4) NOT NULL DEFAULT 0"
                + ", settlement_account VARCHAR(50)"
                + ", reference VARCHAR(100)"
                + ", occurred_at TIMESTAMP NOT NULL"
                + ", posted_at TIMESTAMP NOT NULL"
                + tablePk
                + ", CONSTRAINT uq_profit_ledger_source UNIQUE (source_type, source_id, event_type, product_id, line_key)"
                + ", CONSTRAINT uq_profit_ledger_posting_key UNIQUE (posting_key)"
                + ")");

        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS period_close ("
                + "period VARCHAR(7) NOT NULL"
                + ", closed_at TIMESTAMP NOT NULL"
                + ", closed_by BIGINT"
                + ", frozen_revenue DECIMAL(19,4) NOT NULL DEFAULT 0"
                + ", frozen_cogs DECIMAL(19,4) NOT NULL DEFAULT 0"
                + ", frozen_expense DECIMAL(19,4) NOT NULL DEFAULT 0"
                + ", PRIMARY KEY (period)"
                + ")");
    }

    private void createPurchasePayments(Dialect dialect) {
        String id = switch (dialect) {
            case H2 -> "id BIGINT GENERATED BY DEFAULT AS IDENTITY";
            case MYSQL -> "id BIGINT NOT NULL AUTO_INCREMENT";
            case SQLITE -> "id INTEGER PRIMARY KEY AUTOINCREMENT";
        };
        String tablePk = dialect == Dialect.SQLITE ? "" : ", PRIMARY KEY (id)";
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS purchase_payments ("
                + id
                + ", purchase_id BIGINT NOT NULL"
                + ", amount DECIMAL(19,2) NOT NULL"
                + ", payment_method VARCHAR(30) NOT NULL DEFAULT 'CASH'"
                + ", employee_shift_id BIGINT"
                + ", drawer_movement_id BIGINT"
                + ", payment_date TIMESTAMP NOT NULL"
                + ", recorded_by_id BIGINT NOT NULL"
                + ", notes VARCHAR(255)"
                + tablePk
                + ")");
    }

    private void createLedgerIndexes(Dialect dialect) {
        String[][] indexes = {
                {"idx_inventory_ledger_product", "inventory_ledger", "product_id"},
                {"idx_inventory_ledger_occurred", "inventory_ledger", "occurred_at"},
                {"idx_inventory_ledger_source", "inventory_ledger", "source_type, source_id"},
                {"idx_profit_ledger_period", "profit_ledger", "period"},
                {"idx_profit_ledger_occurred", "profit_ledger", "occurred_at"},
                {"idx_profit_ledger_product", "profit_ledger", "product_id"},
                {"idx_profit_ledger_source", "profit_ledger", "source_type, source_id"},
                {"idx_purchase_payment_purchase", "purchase_payments", "purchase_id"},
                {"idx_purchase_payment_date", "purchase_payments", "payment_date"},
                {"idx_purchase_payment_recorded_by", "purchase_payments", "recorded_by_id"},
        };
        for (String[] index : indexes) {
            if (dialect == Dialect.MYSQL) {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                        Integer.class, index[1], index[0]);
                if (count != null && count > 0) continue;
                jdbcTemplate.execute("CREATE INDEX " + index[0] + " ON " + index[1] + " (" + index[2] + ")");
            } else {
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + index[0] + " ON " + index[1] + " (" + index[2] + ")");
            }
        }
    }

    private void ensureColumn(Dialect dialect, String table, String column, String type, String defaultValue) {
        if (columnExists(dialect, table, column)) {
            jdbcTemplate.update("UPDATE " + table + " SET " + column + " = " + defaultValue
                    + " WHERE " + column + " IS NULL");
            return;
        }
        if (!tableExists(dialect, table)) {
            // A feature this database never had gets its whole table from the schema
            // the app creates, already carrying the column — there is nothing to widen.
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type
                + " DEFAULT " + defaultValue);
        jdbcTemplate.update("UPDATE " + table + " SET " + column + " = " + defaultValue
                + " WHERE " + column + " IS NULL");
        log.info("Added {}.{} {}", table, column, type);
    }

    private void ensureNullableColumn(Dialect dialect, String table, String column, String type) {
        if (columnExists(dialect, table, column) || !tableExists(dialect, table)) return;
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        log.info("Added {}.{} {}", table, column, type);
    }

    /** Whether this database has the table at all — an ALTER on one it lacks is a startup failure. */
    private boolean tableExists(Dialect dialect, String table) {
        if (dialect == Dialect.SQLITE) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                    Integer.class, table);
            return count != null && count > 0;
        }
        String predicate = dialect == Dialect.H2
                ? "TABLE_SCHEMA = CURRENT_SCHEMA() AND UPPER(TABLE_NAME) = ?"
                : "TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE " + predicate,
                Integer.class,
                dialect == Dialect.H2 ? table.toUpperCase(Locale.ROOT) : table);
        return count != null && count > 0;
    }

    private boolean columnExists(Dialect dialect, String table, String column) {
        if (dialect == Dialect.SQLITE) {
            List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")");
            return columns.stream().anyMatch(c -> column.equalsIgnoreCase(String.valueOf(c.get("name"))));
        }
        String predicate = dialect == Dialect.H2
                ? "TABLE_SCHEMA = CURRENT_SCHEMA() AND UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?"
                : "TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE " + predicate,
                Integer.class,
                dialect == Dialect.H2 ? table.toUpperCase(Locale.ROOT) : table,
                dialect == Dialect.H2 ? column.toUpperCase(Locale.ROOT) : column);
        return count != null && count > 0;
    }

    private void ensureUniqueIndex(Dialect dialect, String indexName, String table, String column) {
        boolean exists;
        if (dialect == Dialect.SQLITE) {
            List<Map<String, Object>> indexes = jdbcTemplate.queryForList("PRAGMA index_list(" + table + ")");
            exists = indexes.stream().anyMatch(index ->
                    indexName.equalsIgnoreCase(String.valueOf(index.get("name"))));
        } else {
            String tableClause = dialect == Dialect.H2
                    ? "TABLE_SCHEMA = CURRENT_SCHEMA() AND UPPER(TABLE_NAME) = ? AND UPPER(INDEX_NAME) = ?"
                    : "TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA."
                            + (dialect == Dialect.H2 ? "INDEXES" : "STATISTICS")
                            + " WHERE " + tableClause,
                    Integer.class,
                    dialect == Dialect.H2 ? table.toUpperCase(Locale.ROOT) : table,
                    dialect == Dialect.H2 ? indexName.toUpperCase(Locale.ROOT) : indexName);
            exists = count != null && count > 0;
        }
        if (exists) return;
        Integer duplicates = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (SELECT " + column + " FROM "
                + table + " WHERE " + column + " IS NOT NULL GROUP BY " + column
                + " HAVING COUNT(*) > 1) duplicate_values", Integer.class);
        if (duplicates != null && duplicates > 0) {
            throw new IllegalStateException("Cannot enforce unique stock-count sync keys: duplicate values exist");
        }
        if (dialect == Dialect.MYSQL) {
            jdbcTemplate.execute("CREATE UNIQUE INDEX " + indexName + " ON " + table + " (" + column + ")");
        } else {
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + indexName
                    + " ON " + table + " (" + column + ")");
        }
    }
}