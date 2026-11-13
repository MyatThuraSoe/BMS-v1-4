package com.bms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

@Component
public class CostPrecisionSchemaRepair implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CostPrecisionSchemaRepair.class);

    private static final String[][] COST_COLUMNS = {
            {"products", "cost_price"},
            {"sale_items", "cost_price_at_sale"},
            {"stock_movements", "valuation_amount"},
            {"product_price_history", "old_price"},
            {"product_price_history", "new_price"},
            {"product_price_history", "purchase_unit_price"},
            {"product_price_history", "inventory_value_delta"},
            {"purchase_items", "unit_cost"},
            {"purchase_items", "landed_unit_cost"},
            {"purchase_items", "base_unit_cost"},
            {"order_items", "cost_price_at_order"},
            {"count_sheet_items", "valuation_amount"},
            {"count_sheets", "variance_value"}
    };

    private final JdbcTemplate jdbcTemplate;

    public CostPrecisionSchemaRepair(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String url = connection.getMetaData().getURL().toLowerCase(Locale.ROOT);
            if (url.contains(":sqlite:")) {
                // SQLite NUMERIC affinity does not enforce a declared decimal scale.
                return;
            }
            boolean isH2 = url.contains(":h2:");
            if (!isH2 && !url.contains(":mysql:")) {
                throw new IllegalStateException("Unsupported database for cost precision migration");
            }
            for (String[] target : COST_COLUMNS) {
                widen(target[0], target[1], isH2);
            }
        }
    }

    private void widen(String table, String column, boolean isH2) {
        String predicate = isH2
                ? "TABLE_SCHEMA = CURRENT_SCHEMA() AND UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?"
                : "TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        ColumnMetadata metadata = jdbcTemplate.queryForObject(
                "SELECT NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE, COLUMN_DEFAULT "
                        + "FROM INFORMATION_SCHEMA.COLUMNS WHERE " + predicate,
                (rs, rowNum) -> new ColumnMetadata(rs.getInt("NUMERIC_PRECISION"), rs.getInt("NUMERIC_SCALE"),
                        "YES".equals(rs.getString("IS_NULLABLE")), rs.getString("COLUMN_DEFAULT")),
                isH2 ? table.toUpperCase(Locale.ROOT) : table,
                isH2 ? column.toUpperCase(Locale.ROOT) : column);
        if (metadata.scale() >= 6 && metadata.precision() >= 19) {
            return;
        }
        int scale = Math.max(6, metadata.scale());
        // Preserve the existing integer capacity when adding fractional digits.
        int precision = Math.max(19, metadata.precision() + scale - metadata.scale());
        String type = "DECIMAL(" + precision + "," + scale + ")";
        if (isH2) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE " + type);
        } else {
            String attributes = metadata.nullable() ? " NULL" : " NOT NULL";
            if (metadata.defaultValue() != null) {
                attributes += " DEFAULT " + new BigDecimal(metadata.defaultValue()).toPlainString();
            }
            jdbcTemplate.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + type + attributes);
        }
        log.info("Widened {}.{} to {}", table, column, type);
    }

    private record ColumnMetadata(int precision, int scale, boolean nullable, String defaultValue) {
    }
}
