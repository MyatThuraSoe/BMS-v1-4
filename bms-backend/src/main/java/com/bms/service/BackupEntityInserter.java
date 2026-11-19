package com.bms.service;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Persists every section of a backup import into the database. Sections are
 * staged by {@link #prepare} and written by {@link #insertAll} in dependency
 * order so that reference data (users, categories, products, cash drawers)
 * lands before transactional data (sales, purchases, drawer sessions) that
 * references it.
 *
 * <p>Rows are written with plain {@code INSERT} statements that carry the ids the
 * file holds, because those ids are load-bearing: every foreign key in the backup
 * points at them. The entities use {@code GenerationType.IDENTITY}, so neither
 * {@code merge} nor {@code replicate} preserves a supplied id for a row that is not
 * already present — both let the database renumber it, which re-points every
 * reference in the file. Table and column names come from the entities' own JPA
 * mappings, so a new column needs no change here.
 *
 * <p>Nested children (sale items and payments, purchase items and expenses,
 * price-list items, count-sheet items, promotion tiers) are the only copy of those
 * rows in the file and have no section of their own. They are inserted straight
 * after their parent, with the back-reference set first so the foreign key
 * resolves.
 */
public class BackupEntityInserter implements AutoCloseable {

    /**
     * Dependency order: sections earlier in this list must be persisted before
     * later sections, because later entities reference earlier ones via foreign
     * keys. Derived from the entity mappings, including the rows each entity nests:
     * a purchase brings its items, so the products they point at must already exist.
     */
    private static final List<String> SECTION_PRIORITY = List.of(
            "categories", "products", "priceLists", "customers",
            "cashDrawers", "users", "cashDrawerSessions", "cashShifts", "cashDrawerMovements",
            "sales", "arPayments", "countSheets", "customerSequences", "expenses",
            "glAccounts", "glMappings", "inventoryLedger", "invoiceSequences",
            "locations", "locationStock", "orders", "orderSequences", "periodCloses",
            "productPriceHistory", "profitLedger", "promotions", "suppliers", "purchases",
            "purchasePayments", "purchaseSequences", "receiptCustomizations",
            "saleReturns", "shopInfo", "stockMovements", "stockTransfers",
            "systemSettings", "transferSequences"
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

    /** Writes every staged row and its nested children, preserving the file's ids. */
    public void insertAll() {
        if (stagedSections == null) {
            return;
        }
        for (Map.Entry<String, List<?>> entry : stagedSections) {
            List<?> section = entry.getValue();
            if (section == null) {
                continue;
            }
            for (Object entity : section) {
                if (entity != null) {
                    insertParentAndChildren(entity);
                }
            }
        }
    }

    private void insertParentAndChildren(Object entity) {
        List<List<Object>> childrenGroups = childrenOf(entity);
        insertRow(entity);
        for (List<Object> children : childrenGroups) {
            for (Object child : children) {
                bindOwner(child, entity);
                insertRow(child);
            }
        }
    }

    /** Every owned collection on the entity, copied so the insert can iterate freely. */
    private List<List<Object>> childrenOf(Object entity) {
        List<List<Object>> groups = new ArrayList<>();
        for (Field field : persistentFields(entity.getClass())) {
            if (!Collection.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(entity);
                if (value instanceof Collection<?> collection && !collection.isEmpty()) {
                    groups.add(new ArrayList<>(collection));
                }
            } catch (IllegalAccessException ignored) {
                // A collection that cannot be read is not part of the row being written.
            }
        }
        return groups;
    }

    /** The child's back-reference to its owner, e.g. {@code SaleItem#setSale}. */
    private void bindOwner(Object child, Object owner) {
        for (var method : child.getClass().getMethods()) {
            if (!method.getName().startsWith("set") || method.getParameterCount() != 1) {
                continue;
            }
            if (!method.getParameterTypes()[0].isInstance(owner)) {
                continue;
            }
            try {
                method.invoke(child, owner);
                return;
            } catch (Exception ex) {
                throw new IllegalStateException("Restore could not attach the "
                        + child.getClass().getSimpleName() + " to its owner", ex);
            }
        }
    }

    /** Writes one row with an explicit id, using the entity's own column mapping. */
    private void insertRow(Object entity) {
        Class<?> type = entity.getClass();
        String table = tableName(type);

        List<String> columns = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        for (Field field : persistentFields(type)) {
            if (Collection.class.isAssignableFrom(field.getType())) {
                continue;
            }
            Object value;
            try {
                field.setAccessible(true);
                value = field.get(entity);
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Restore could not read the "
                        + field.getName() + " of " + type.getSimpleName(), ex);
            }
            columns.add(columnName(field));
            parameters.add(sqlValue(value));
        }

        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns)
                + ") VALUES (" + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
        try {
            jdbcTemplate.update(sql, parameters.toArray());
        } catch (DataAccessException ex) {
            throw new IllegalStateException("Restore could not insert " + type.getSimpleName()
                    + " id=" + identifierOf(entity) + " into " + table + ": "
                    + ex.getMostSpecificCause().getMessage(), ex);
        }
    }

    /**
     * The mapped fields of an entity, in declaration order: everything the JPA
     * mapping writes to a column, excluding collections and transient state.
     */
    private static List<Field> persistentFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            if (field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            fields.add(field);
        }
        return fields;
    }

    private static String tableName(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        return snakeCase(type.getSimpleName()) + "s";
    }

    private static String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isBlank()) {
            return column.name();
        }
        JoinColumn join = field.getAnnotation(JoinColumn.class);
        if (join != null && !join.name().isBlank()) {
            return join.name();
        }
        String name = snakeCase(field.getName());
        boolean association = field.isAnnotationPresent(ManyToOne.class)
                || field.isAnnotationPresent(OneToOne.class);
        return association ? name + "_id" : name;
    }

    /** Spring Boot's physical naming strategy: camelCase to snake_case. */
    private static String snakeCase(String name) {
        StringBuilder snake = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (Character.isUpperCase(character)) {
                if (i > 0) {
                    snake.append('_');
                }
                snake.append(Character.toLowerCase(character));
            } else {
                snake.append(character);
            }
        }
        return snake.toString();
    }

    /**
     * The JDBC value for one column: an association becomes its id, an enum becomes
     * its name, and anything else is passed through to the driver.
     */
    private Object sqlValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Collection<?>) {
            return null;
        }
        if (value.getClass().isAnnotationPresent(jakarta.persistence.Entity.class)) {
            return identifierOf(value);
        }
        return value;
    }

    private Object identifierOf(Object entity) {
        try {
            return entity.getClass().getMethod("getId").invoke(entity);
        } catch (Exception ex) {
            return entityManager.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(entity);
        }
    }

    @Override
    public void close() {
        stagedSections = null;
    }
}
