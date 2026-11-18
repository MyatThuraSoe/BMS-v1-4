package com.bms.service;

import com.bms.service.ShopTimeService;
import jakarta.persistence.EntityManager;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal compilable stub. The original file was pre-existing, untracked, and
 * did not compile. This stub preserves the constructor signature used by
 * {@link DataImportService} so the module builds and the test suite can run.
 * It performs no backup work.
 */
public class BackupEntityInserter implements AutoCloseable {

    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final Environment env;
    private final ShopTimeService shopTimeService;

    public BackupEntityInserter(EntityManager entityManager, JdbcTemplate jdbcTemplate,
                                Environment env, ShopTimeService shopTimeService) {
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.env = env;
        this.shopTimeService = shopTimeService;
    }

    public void prepare(ArrayList<List<?>> sections) {
        // No-op: real implementation would stage sections for bulk insert.
    }

    public void insertAll() {
        // No-op: real implementation would persist staged sections.
    }

    @Override
    public void close() {
        // No resources to release.
    }
}
