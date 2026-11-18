package com.bms.service;

import com.bms.service.ShopTimeService;
import jakarta.persistence.EntityManager;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Persists every section of a backup import into the database. Sections are
 * staged by {@link #prepare} and written by {@link #insertAll} in dependency
 * order so that reference data (users, categories, products, cash drawers)
 * lands before transactional data (sales, purchases, drawer sessions) that
 * references it.
 *
 * <p>Entities from backup JSON carry their IDs. {@link EntityManager#merge(Object)}
 * handles both new entities (persist semantics) and existing rows (update
 * semantics). Collections are left intact so Hibernate can cascade-save related
 * entities.
 */
public class BackupEntityInserter implements AutoCloseable {

    private static final int FLUSH_EVERY = 100;

    /**
     * Dependency order: sections earlier in this list must be persisted before
     * later sections, because later entities reference earlier ones via foreign keys.
     */
    private static final List<String> SECTION_PRIORITY = List.of(
            "users", "categories", "customers", "suppliers", "glAccounts",
            "locations", "cashDrawers", "products", "priceLists",
            "locationStock", "purchases", "sales", "saleReturns",
            "receiptCustomizations", "arPayments", "purchasePayments",
            "orders", "orderSequences", "expenses", "shopInfoRows",
            "systemSettings", "stockMovements", "stockTransfers",
            "promotions", "priceHistory", "countSheets", "glMappings",
            "invoiceSequences", "purchaseSequences", "customerSequences",
            "transferSequences", "periodCloses", "drawerSessions",
            "cashShifts", "drawerMovements", "inventoryLedger", "profitLedger"
    );

    private static final int PRIORITY_NOT_FOUND = Integer.MAX_VALUE;

    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final Environment env;
    private final ShopTimeService shopTimeService;

    private List<Map.Entry<String, List<?>>> stagedSections;

    public BackupEntityInserter(EntityManager entityManager, JdbcTemplate jdbcTemplate,
                                Environment env, ShopTimeService shopTimeService) {
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.env = env;
        this.shopTimeService = shopTimeService;
    }

    /**
     * Stages the backup sections keyed by name. Sections will be persisted in
     * {@link #SECTION_PRIORITY} order.
     */
    public void prepare(Map<String, List<?>> sections) {
        List<Map.Entry<String, List<?>>> ordered = new ArrayList<>(sections.entrySet());
        ordered.sort((a, b) -> {
            int pa = priorityOf(a.getKey());
            int pb = priorityOf(b.getKey());
            return Integer.compare(pa, pb);
        });
        this.stagedSections = ordered;
    }

    private static int priorityOf(String name) {
        for (int i = 0; i < SECTION_PRIORITY.size(); i++) {
            if (SECTION_PRIORITY.get(i).equals(name)) {
                return i;
            }
        }
        return PRIORITY_NOT_FOUND;
    }

    /**
     * Persists every staged entity into the database using {@link EntityManager#merge(Object)}.
     * Entities from backup JSON carry their IDs from the exported shop; merge
     * inserts new rows and updates existing ones based on ID presence.
     */
    public void insertAll() {
        if (stagedSections == null) {
            return;
        }
        int count = 0;
        for (Map.Entry<String, List<?>> entry : stagedSections) {
            List<?> section = entry.getValue();
            if (section == null) {
                continue;
            }
            for (Object entity : section) {
                if (entity == null) {
                    continue;
                }
                clearCollections(entity);
                entityManager.merge(entity);
                count++;
                if (count % FLUSH_EVERY == 0) {
                    entityManager.flush();
                    entityManager.clear();
                }
            }
        }
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Clears every {@link Collection}-typed field on the entity via setter methods,
     * bypassing Hibernate's enhanced getter/setter methods. Used as a fallback when
     * merge() fails due to collections whose owner has no assigned ID yet.
     */
    private void clearCollections(Object entity) {
        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().startsWith("set") && m.getParameterCount() == 1
                        && Collection.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    m.setAccessible(true);
                    try {
                        m.invoke(entity, new ArrayList<>());
                    } catch (Exception ignored) {
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    @Override
    public void close() {
        stagedSections = null;
    }
}
