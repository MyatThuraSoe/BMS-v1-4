package com.bms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * One-off schema repair for the discount feature.
 *
 * When Hibernate created shop_info.discount_type it used the database's
 * NATIVE enum type (H2: ENUM('AMOUNT','PERCENTAGE'), possibly a CHECK
 * constraint elsewhere). Adding the FIXED value later makes every UPDATE
 * fail, and ddl-auto=update never alters existing column types.
 *
 * Repair (idempotent):
 *  1. Convert the column to VARCHAR(20) if the DB reports it as enum-typed.
 *  2. Drop any CHECK constraints mentioning DISCOUNT_TYPE (legacy path).
 *
 * Failures are logged and swallowed so they can never block startup.
 */
@Component
public class DiscountTypeCheckConstraintRepair implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DiscountTypeCheckConstraintRepair.class);

    private final JdbcTemplate jdbcTemplate;

    public DiscountTypeCheckConstraintRepair(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String url = connection.getMetaData().getURL().toLowerCase();
            boolean isH2 = url.contains(":h2:");

            // --- Step 1: convert native ENUM column to VARCHAR ---
            String dataType;
            if (isH2) {
                dataType = jdbcTemplate.queryForObject(
                        "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE UPPER(TABLE_NAME) = 'SHOP_INFO' AND UPPER(COLUMN_NAME) = 'DISCOUNT_TYPE'",
                        String.class);
            } else {
                dataType = jdbcTemplate.queryForObject(
                        "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_info' "
                                + "AND COLUMN_NAME = 'discount_type'",
                        String.class);
            }

            if (dataType != null && dataType.equalsIgnoreCase("ENUM")) {
                if (isH2) {
                    jdbcTemplate.execute(
                            "ALTER TABLE shop_info ALTER COLUMN discount_type SET DATA TYPE VARCHAR(20)");
                } else {
                    jdbcTemplate.execute(
                            "ALTER TABLE shop_info MODIFY COLUMN discount_type VARCHAR(20)");
                }
                log.info("DiscountTypeCheckConstraintRepair: converted shop_info.discount_type from ENUM to VARCHAR(20)");
            }

            // --- Step 2: drop legacy CHECK constraints referencing the column ---
            List<String> constraintNames;
            if (isH2) {
                constraintNames = jdbcTemplate.queryForList(
                        "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS "
                                + "WHERE UPPER(CHECK_CLAUSE) LIKE '%DISCOUNT_TYPE%'",
                        String.class);
                for (String name : constraintNames) {
                    jdbcTemplate.execute("ALTER TABLE SHOP_INFO DROP CONSTRAINT \"" + name + "\"");
                }
            } else {
                constraintNames = jdbcTemplate.queryForList(
                        "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = DATABASE() "
                                + "AND UPPER(CHECK_CLAUSE) LIKE '%DISCOUNT_TYPE%'",
                        String.class);
                for (String name : constraintNames) {
                    jdbcTemplate.execute("ALTER TABLE shop_info DROP CHECK `" + name + "`");
                }
            }
            if (!constraintNames.isEmpty()) {
                log.info("DiscountTypeCheckConstraintRepair: dropped {} stale DISCOUNT_TYPE check constraint(s)",
                        constraintNames.size());
            }
        } catch (Exception e) {
            log.warn("DiscountTypeCheckConstraintRepair skipped (non-fatal): {}", e.getMessage());
        }
    }
}
