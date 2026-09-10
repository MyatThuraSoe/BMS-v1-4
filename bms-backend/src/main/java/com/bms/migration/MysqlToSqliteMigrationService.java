package com.bms.migration;

import com.bms.sqlite.JavaTimeNormalizingConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One-time migration tool: reads every table from the existing MySQL database and
 * copies it into the SQLite file the app now runs on.
 *
 * <p>Design:
 * <ul>
 *   <li>Runs only when {@code lumipos.migration.mysql-to-sqlite=true} (or env
 *       {@code LUMIPOS_MIGRATE_MYSQL_TO_SQLITE=1}), so a normal start never touches it.</li>
 *   <li>Uses raw JDBC (DriverManager) for BOTH sides — no second DataSource bean is
 *       ever created in the Spring context.</li>
 *   <li>Schema is copied generically from MySQL JDBC metadata and mapped to SQLite
 *       affinity types (TEXT/INTEGER/REAL/NUMERIC/BLOB). Foreign keys are enabled
 *       only after the copy (SQLite FKs are OFF by default within the tool).</li>
 *   <li>Row data is copied in batches of 500 and committed per table.</li>
 * </ul>
 *
 * <p>Idempotent: tables are DROPPED and recreated, so re-running replaces data.
 */
@Component
@ConditionalOnProperty(prefix = "lumipos.migration", name = "mysql-to-sqlite", havingValue = "true")
public class MysqlToSqliteMigrationService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MysqlToSqliteMigrationService.class);

    private static final int BATCH_SIZE = 500;

    @Value("${lumipos.migration.mysql.url}")
    private String mysqlUrl;

    @Value("${lumipos.migration.mysql.username}")
    private String mysqlUsername;

    @Value("${lumipos.migration.mysql.password:}")
    private String mysqlPassword;

    @Value("${lumipos.migration.sqlite.path}")
    private String sqlitePath;

    @Override
    public void run(ApplicationArguments args) {
        log.info("============================================================");
        log.info("MySQL -> SQLite migration requested. Source: {}", shortenUrl(mysqlUrl));
        log.info("                                    Target: {}", sqlitePath);
        log.info("============================================================");

        long startNanos = System.nanoTime();
        int okTables = 0;
        int failedTables = 0;

        try (Connection mysql = DriverManager.getConnection(mysqlUrl, mysqlUsername, mysqlPassword);
             Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {

            executeDdl(sqlite, "PRAGMA busy_timeout=10000");
            executeDdl(sqlite, "PRAGMA journal_mode=WAL");
            sqlite.setAutoCommit(false);

            List<String> tables = listTables(mysql);
            log.info("Found {} table(s) in MySQL", tables.size());

            for (String table : tables) {
                try {
                    copyTable(mysql, sqlite, table);
                    okTables++;
                } catch (Exception e) {
                    failedTables++;
                    log.error("Migration FAILED for table '{}': {}", table, e.getMessage(), e);
                }
            }

            sqlite.commit();
            log.info("Migration finished in {}. {} table(s) migrated, {} failed.",
                    formatDuration(startNanos), okTables, failedTables);

            if (failedTables > 0) {
                log.warn("Some tables failed — review the errors above. Retry by setting the flag again.");
            } else {
                log.info("Migration complete. Restart LumiPOS normally (remove the migrate flag) to use the SQLite data.");
            }
        } catch (SQLException e) {
            log.error("Unable to run migration. Check that MySQL is reachable and the SQLite path is writable.", e);
            log.warn("Continuing startup without migrating (SQLite may be empty).");
        }
    }

    private List<String> listTables(Connection mysql) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement st = mysql.createStatement();
             ResultSet rs = st.executeQuery("SHOW TABLES")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if ("flyway_schema_history".equalsIgnoreCase(name)) {
                    continue;
                }
                tables.add(name);
            }
        }
        return tables;
    }

    private List<String> primaryKeyColumns(Connection mysql, String table) throws SQLException {
        List<String> pk = new ArrayList<>();
        DatabaseMetaData md = mysql.getMetaData();
        try (ResultSet rs = md.getPrimaryKeys(null, null, table)) {
            while (rs.next()) {
                pk.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pk;
    }

    private void copyTable(Connection mysql, Connection sqlite, String table) throws SQLException {
        List<String> pkColumns = primaryKeyColumns(mysql, table);

        try (Statement st = mysql.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM `" + table + "`")) {

            ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();
            List<String> colNames = new ArrayList<>(colCount);
            List<String> colTypes = new ArrayList<>(colCount);
            List<Boolean> notNull = new ArrayList<>(colCount);

            for (int i = 1; i <= colCount; i++) {
                colNames.add(md.getColumnName(i));
                colTypes.add(sqliteType(md.getColumnTypeName(i)));
                boolean required = md.isNullable(i) == ResultSetMetaData.columnNoNulls
                        || pkColumns.contains(md.getColumnName(i));
                notNull.add(required);
            }

            executeDdl(sqlite, "DROP TABLE IF EXISTS `" + table + "`");
            executeDdl(sqlite, buildCreateTable(table, colNames, colTypes, notNull, pkColumns));

            int rows = copyRows(sqlite, table, colNames, rs);
            sqlite.commit();
            log.info("Migrated table '{}' -> {} row(s)", table, rows);
        }
    }

    private String buildCreateTable(String table, List<String> names, List<String> types,
                                    List<Boolean> notNull, List<String> pkColumns) {
        StringBuilder sb = new StringBuilder("CREATE TABLE `").append(table).append("` (");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('`').append(names.get(i)).append('`').append(' ').append(types.get(i));
            if (notNull.get(i)) {
                sb.append(" NOT NULL");
            }
        }
        if (!pkColumns.isEmpty()) {
            sb.append(", PRIMARY KEY (");
            for (int i = 0; i < pkColumns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('`').append(pkColumns.get(i)).append('`');
            }
            sb.append(')');
        }
        return sb.append(')').toString();
    }

    private int copyRows(Connection sqlite, String table, List<String> colNames,
                         ResultSet rs) throws SQLException {
        StringBuilder insert = new StringBuilder("INSERT INTO `").append(table).append("` (");
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) {
                insert.append(", ");
            }
            insert.append('`').append(colNames.get(i)).append('`');
        }
        insert.append(") VALUES (");
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) {
                insert.append(", ");
            }
            insert.append('?');
        }
        insert.append(')');

        int total = 0;
        try (PreparedStatement ps = sqlite.prepareStatement(insert.toString())) {
            while (rs.next()) {
                for (int i = 0; i < colNames.size(); i++) {
                    Object value = rs.getObject(i + 1);
                    bindValue(ps, i + 1, value);
                }
                ps.addBatch();
                total++;
                if (total % BATCH_SIZE == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        return total;
    }

    private void bindValue(PreparedStatement ps, int idx, Object v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.NULL);
        } else if (v instanceof byte[] bytes) {
            ps.setBytes(idx, bytes);
        } else if (v instanceof BigDecimal bd) {
            ps.setBigDecimal(idx, bd);
        } else if (v instanceof java.util.Date date) {
            // Store dates as full ISO datetime TEXT ("2026-09-10 12:30:00.123",
            // midnight for date-only columns). sqlite-jdbc's setDate/setTimestamp
            // persist java.util.Date as an epoch MILLIS INTEGER, which Hibernate's
            // SQLiteDialect cannot re-parse ("Unparseable date: '1788975000000'"),
            // and a bare "2026-09-10" fails getDate ("Unparseable date"). The full
            // text form matches exactly what the app's own JavaTimeNormalizer writes,
            // so WHERE date_col = ? comparisons remain consistent.
            ps.setString(idx, JavaTimeNormalizingConnection.toSqliteText(date));
        } else if (v instanceof java.time.temporal.TemporalAccessor t) {
            // MySQL Connector/J returns java.time objects (e.g. LocalDateTime) for
            // DATETIME/TIMESTAMP columns. Their toString() is ISO "T" form —
            // "2026-08-25T08:26:13.549486" — which sqlite-jdbc's getTimestamp can
            // never parse. Normalize to the same space-separated text form.
            ps.setString(idx, JavaTimeNormalizingConnection.toSqliteText(t));
        } else if (v instanceof Boolean b) {
            ps.setInt(idx, b ? 1 : 0);
        } else if (v instanceof Number) {
            ps.setObject(idx, v);
        } else {
            ps.setString(idx, v.toString());
        }
    }

    private String sqliteType(String mysqlType) {
        String t = mysqlType.toUpperCase(Locale.ROOT);
        if (t.contains("BLOB") || t.contains("BINARY") || t.contains("IMAGE") || t.contains("LONGVARBINARY")) {
            return "BLOB";
        }
        if (t.contains("INT")) {
            return "INTEGER";
        }
        if (t.contains("DEC") || t.contains("NUM") || t.contains("NUMBER") || t.contains("FIXED")) {
            return "NUMERIC";
        }
        if (t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB")) {
            return "REAL";
        }
        if (t.contains("BOOL") || t.contains("BIT")) {
            return "INTEGER";
        }
        // CHAR/VARCHAR/TEXT/ENUM/SET/JSON/DATE/TIME/DATETIME/TIMESTAMP all store fine as TEXT.
        return "TEXT";
    }

    private void executeDdl(Connection sqlite, String sql) throws SQLException {
        // sqlite-jdbc requires each Statement to be closed before any other
        // statement runs on the same connection — an open statement keeps a
        // transaction "in progress" and makes commit() fail with SQLITE_BUSY.
        try (Statement st = sqlite.createStatement()) {
            st.execute(sql);
        }
    }

    private String shortenUrl(String url) {
        return url == null ? "null" : url.replaceAll("password=[^&]*", "password=***");
    }

    private String formatDuration(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }
}