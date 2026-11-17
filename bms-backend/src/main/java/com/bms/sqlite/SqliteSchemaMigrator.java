package com.bms.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates and upgrades the SQLite schema in place of Hibernate's {@code ddl-auto: update}.
 *
 * <p>Hibernate reads the existing schema with a single
 * {@code DatabaseMetaData.getColumns(catalog, schema, null, null)} call, and sqlite-jdbc
 * compiles that into a compound SELECT carrying one term per column. SQLite caps compound
 * terms at 500 and the cap is fixed at compile time, so from roughly 500 columns onwards
 * every start dies inside the SessionFactory build — including the start right after an
 * upgrade that pushed the schema past the cap. This class only ever asks about one table
 * at a time, which the driver answers without a compound SELECT, so schema size stops
 * mattering.
 *
 * <p>Statements come from {@code db/sqlite/schema.sql}, which is Hibernate's own export
 * of the entity model and is what {@code ddl-auto: update} used to run. That script is also
 * the source of the enum value lists: an existing table whose {@code CHECK (col in (...))}
 * no longer covers the current model is rebuilt here, because SQLite cannot widen a check
 * in place and an upgrade that only added values would leave a shop rejecting them forever.
 */
@Component
@Profile("sqlite")
public class SqliteSchemaMigrator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SqliteSchemaMigrator.class);

    static final String SCRIPT_LOCATION = "classpath:db/sqlite/schema.sql";

    /** Where a column's type declaration stops and its constraints begin. */
    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of(
            "not", "null", "unique", "check", "default", "primary", "references",
            "collate", "generated", "autoincrement", "constraint");

    /** Body clauses of a CREATE TABLE that describe the table, not a column. */
    private static final Set<String> TABLE_LEVEL_KEYWORDS = Set.of(
            "primary", "unique", "check", "foreign", "constraint");

    /** An enum column's allowed values, as Hibernate writes them: {@code check (col in ('A','B'))}. */
    private static final Pattern ENUM_CHECK = Pattern.compile(
            "check\\s*\\(\\s*([^\\s(),]+)\\s+in\\s*\\(([^()]*)\\)", Pattern.CASE_INSENSITIVE);

    /** Name given to the replacement table while a stale one is being rebuilt. */
    private static final String STAGING_SUFFIX = "__new";

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** Marks the copies this class wrote, so pruning matches nothing else in the folder. */
    private static final String UPGRADE_BACKUP_INFIX = ".pre-upgrade-";

    /** How many of those copies a shop keeps. */
    static final String UPGRADE_KEEP_SETTING = "sqlite.upgradeBackup.keep";

    static final int DEFAULT_UPGRADE_BACKUPS_KEPT = 3;

    private final DataSource dataSource;
    private final Resource script;

    public SqliteSchemaMigrator(DataSource dataSource, ResourceLoader resourceLoader) {
        this.dataSource = dataSource;
        this.script = resourceLoader.getResource(SCRIPT_LOCATION);
    }

    @Override
    public void afterPropertiesSet() throws SQLException, IOException {
        if (!script.exists()) {
            throw new IllegalStateException("SQLite schema script " + SCRIPT_LOCATION
                    + " is missing from the classpath; the SQLite database cannot be created");
        }
        String scriptText = read();
        Map<String, Table> expected = tables(scriptText);
        List<String> tables = statements(scriptText).stream()
                .filter(statement -> statement.toLowerCase(Locale.ROOT).startsWith("create table"))
                .toList();
        List<String> indexes = statements(scriptText).stream()
                .filter(statement -> !statement.toLowerCase(Locale.ROOT).startsWith("create table"))
                .toList();
        try (Connection connection = dataSource.getConnection()) {
            Set<String> alreadyCreated = tableNames(connection);
            execute(connection, tables);
            addMissingColumns(connection, expected, alreadyCreated);
            // Indexes come last: a shop whose tables predate a column cannot have that
            // column's index created until the column itself has been added. A widened enum
            // also has to be refreshed before them, because that rebuild drops the table and
            // carries its own indexes across with it.
            Path backup = refreshCheckConstraints(connection, expected, alreadyCreated);
            execute(connection, indexes);
            // Pruning comes after everything that can still fail, so a start that gave up
            // halfway leaves every copy of the shop's database where it was.
            pruneOldUpgradeBackups(connection, backup);
        }
    }

    private String read() throws IOException {
        try (InputStream in = script.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void execute(Connection connection, List<String> statements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : statements) {
                try {
                    statement.execute(ddl);
                } catch (SQLException ex) {
                    throw new SQLException("LumiPOS could not apply \"" + abbreviate(ddl) + "\": "
                            + ex.getMessage(), ex.getSQLState(), ex.getErrorCode(), ex);
                }
            }
        }
    }

    private static String abbreviate(String ddl) {
        return ddl.length() <= 120 ? ddl : ddl.substring(0, 120) + "...";
    }

    /**
     * Brings tables that survived from an older build up to the current model. SQLite
     * refuses an added column that is NOT NULL without a default, so {@link #declaration}
     * keeps only what it can carry; the value of a new column on existing rows is the job
     * of the backfill runners.
     */
    private void addMissingColumns(Connection connection, Map<String, Table> expected,
                                   Set<String> alreadyCreated) throws SQLException {
        int added = 0;
        try (Statement statement = connection.createStatement()) {
            for (Table table : expected.values()) {
                if (!alreadyCreated.contains(table.name())) {
                    continue;
                }
                Set<String> present = columnNames(statement, table.name());
                for (Column column : table.columns()) {
                    if (present.contains(column.name())) {
                        continue;
                    }
                    String ddl = "alter table " + table.name() + " add column " + column.declaration();
                    try {
                        statement.execute(ddl);
                    } catch (SQLException ex) {
                        throw new SQLException("LumiPOS could not add column '" + column.name()
                                + "' to table '" + table.name() + "': " + ex.getMessage(), ex.getSQLState(),
                                ex.getErrorCode(), ex);
                    }
                    added++;
                }
            }
        }
        if (added > 0) {
            log.info("SQLite schema: added {} missing column(s)", added);
        }
    }

    /**
     * Gives tables that survived from an older build the enum value list of the current
     * model. SQLite has no {@code ALTER ... CHECK}, and a CHECK created with the table can
     * never be widened in place, so a shop that installed before {@code INVENTORY_GAIN}
     * existed keeps rejecting it forever. Such a table is replaced by a copy of itself with
     * the wider list, inside one transaction, after the file has been backed up.
     *
     * <p>Only columns that carry a list on both sides are compared. A table whose old
     * definition had no list at all is left alone: it already accepts everything, and adding
     * a check could reject rows that are already stored.
     *
     * @return the copy written before the first rebuild, or {@code null} when nothing was rebuilt
     */
    private Path refreshCheckConstraints(Connection connection, Map<String, Table> expected,
                                         Set<String> alreadyCreated) throws SQLException, IOException {
        Map<String, Map<String, String>> widenings = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement()) {
            for (Table table : expected.values()) {
                if (!alreadyCreated.contains(table.name()) || table.checks().isEmpty()) {
                    continue;
                }
                String live = liveDefinition(statement, table.name());
                Map<String, String> current = enumChecks(live);
                Map<String, String> wider = new LinkedHashMap<>();
                for (Map.Entry<String, String> check : table.checks().entrySet()) {
                    String allowed = current.get(check.getKey());
                    if (allowed != null && !enumValues(allowed).containsAll(enumValues(check.getValue()))) {
                        wider.put(check.getKey(), check.getValue());
                    }
                }
                if (!wider.isEmpty()) {
                    widenings.put(table.name(), wider);
                }
            }
        }
        if (widenings.isEmpty()) {
            return null;
        }
        log.info("SQLite schema: {} table(s) still hold an older enum value list: {}",
                widenings.size(), widenings.keySet());
        Path backup = backUpDatabaseFile(connection);
        // Rows that already dangled before the upgrade are the shop's own history, so the
        // swap is only refused if it is what orphaned them.
        int violationsBefore = foreignKeyViolations(connection);
        try (Statement statement = connection.createStatement()) {
            for (Map.Entry<String, Map<String, String>> widening : widenings.entrySet()) {
                rebuild(connection, statement, widening.getKey(),
                        liveDefinition(statement, widening.getKey()), widening.getValue());
            }
        }
        int violations = foreignKeyViolations(connection);
        if (violations > violationsBefore) {
            throw new SQLException("LumiPOS stopped: rebuilding " + widenings.keySet() + " left "
                    + violations + " row(s) pointing at a parent that is not there. Restore "
                    + (backup == null ? "the backup" : "file " + backup)
                    + " and start the previous version.");
        }
        if (backup != null) {
            log.warn("SQLite schema: the database was copied to {} before those tables were rebuilt",
                    backup.getFileName());
        }
        return backup;
    }

    /**
     * Swaps one table for an identical definition whose enum lists are the current ones,
     * keeping every column and row it already had. Only the lists change: rebuilding from
     * {@code schema.sql} instead would pull in columns the shop has never had values for and
     * drop columns the current model no longer declares, which is a far larger upgrade than a
     * check that was widened.
     */
    private void rebuild(Connection connection, Statement source, String name, String liveDefinition,
                         Map<String, String> widerLists) throws SQLException {
        String staging = name + STAGING_SUFFIX;
        List<String> columns = new ArrayList<>(columnNames(source, name));
        List<String> legacyIndexes = legacyIndexDefinitions(source, name);
        long rowsBefore = rowCount(source, name);

        // Foreign keys have to be off while the parent is gone, and the pragma does nothing
        // once a transaction has opened, so it is set here rather than inside the block.
        boolean autoCommit = connection.getAutoCommit();
        String foreignKeys = pragma(connection, "foreign_keys");
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=OFF");
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(stagingDefinition(liveDefinition, name, staging, widerLists));
                statement.execute("insert into " + staging + " (" + String.join(", ", columns) + ")"
                        + " select " + String.join(", ", columns) + " from " + name);
                long copied = rowCount(statement, staging);
                if (copied != rowsBefore) {
                    throw new SQLException("the copy holds " + copied + " row(s) where the table has "
                            + rowsBefore);
                }
                statement.execute("drop table " + name);
                statement.execute("alter table " + staging + " rename to " + name);
                if (countRows(statement, "PRAGMA foreign_key_check(" + name + ")") > 0) {
                    throw new SQLException("the rebuilt table points at rows that are not there");
                }
            }
            connection.commit();
            log.info("SQLite schema: widened the enum lists of table '{}' ({} column(s), {} row(s) carried over)",
                    name, widerLists.keySet(), rowsBefore, columns.size());
        } catch (SQLException ex) {
            rollback(connection);
            throw new SQLException("LumiPOS could not widen the allowed values of table '" + name
                    + "', and the table was left exactly as it was: " + ex.getMessage(),
                    ex.getSQLState(), ex.getErrorCode(), ex);
        } finally {
            restoreAutoCommit(connection, autoCommit);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=" + (foreignKeys == null ? "OFF" : foreignKeys));
            } catch (SQLException ignored) {
                log.warn("SQLite schema: could not restore PRAGMA foreign_keys after rebuilding '{}'", name);
            }
        }
        recreateDroppedIndexes(connection, name, legacyIndexes);
    }

    /**
     * The live {@code CREATE TABLE} with the staging name and the current value lists. It is
     * otherwise untouched, so the copy holds what the shop already had.
     */
    static String stagingDefinition(String liveDefinition, String name, String staging,
                                    Map<String, String> widerLists) {
        Matcher matcher = ENUM_CHECK.matcher(liveDefinition);
        StringBuilder widened = new StringBuilder();
        while (matcher.find()) {
            String column = strip(matcher.group(1)).toLowerCase(Locale.ROOT);
            String values = widerLists.getOrDefault(column, matcher.group(2));
            matcher.appendReplacement(widened, Matcher.quoteReplacement(
                    matcher.group(0).replace(matcher.group(2), values)));
        }
        matcher.appendTail(widened);
        return widened.toString().replaceFirst(
                "(?i)^(create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?)[\"`\\[]?" + Pattern.quote(name)
                        + "[\"`\\]]?",
                "$1" + Matcher.quoteReplacement(staging));
    }

    /**
     * Re-creates the indexes the dropped table had that the script does not declare; the ones
     * it does are made moments later by the script's own index pass. A legacy index sitting on
     * a column the current model no longer has cannot be rebuilt and is only reported - the
     * table itself is already correct by then, so losing that one index must not stop a shop.
     */
    private void recreateDroppedIndexes(Connection connection, String name, List<String> definitions) {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : definitions) {
                try {
                    statement.execute(ddl);
                } catch (SQLException ex) {
                    log.warn("SQLite schema: index of table '{}' could not be rebuilt ({}): {}",
                            name, ex.getMessage(), abbreviate(ddl));
                }
            }
        } catch (SQLException ex) {
            log.warn("SQLite schema: indexes of table '{}' were not checked: {}", name, ex.getMessage());
        }
    }

    private static List<String> legacyIndexDefinitions(Statement statement, String name) throws SQLException {
        List<String> definitions = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'index'"
                + " AND tbl_name = '" + name + "' AND sql IS NOT NULL")) {
            while (rs.next()) {
                definitions.add(rs.getString(1).replaceFirst("(?i)^(create\\s+(?:unique\\s+)?index\\s+)",
                        "$1if not exists "));
            }
        }
        return definitions;
    }

    private static String liveDefinition(Statement statement, String name) throws SQLException {
        try (ResultSet rs = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'table'"
                + " AND name = '" + name + "'")) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    private static int foreignKeyViolations(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return countRows(statement, "PRAGMA foreign_key_check");
        }
    }

    private static long rowCount(Statement statement, String table) throws SQLException {
        try (ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static int countRows(Statement statement, String query) throws SQLException {
        try (ResultSet rs = statement.executeQuery(query)) {
            int rows = 0;
            while (rs.next()) {
                rows++;
            }
            return rows;
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ex) {
            log.warn("SQLite schema: the rollback after a failed rebuild did not complete: {}", ex.getMessage());
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ex) {
            log.warn("SQLite schema: could not restore the connection's auto-commit mode: {}", ex.getMessage());
        }
    }

    private static String pragma(Connection connection, String name) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException ex) {
            return null;
        }
    }

    /**
     * A copy of the whole database next to the original, taken before the first rebuild of a
     * start. A rebuild is the only step here that can lose anything, so it is the only one
     * that is guarded, and one copy per start is enough to get back to.
     */
    private static Path backUpDatabaseFile(Connection connection) throws SQLException, IOException {
        Path source = databaseFile(connection);
        if (source == null) {
            log.warn("SQLite schema: no database file on disk behind this connection; rebuilding without a copy");
            return null;
        }
        String file = source.toString();
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException ex) {
            log.warn("SQLite schema: the write-ahead log of '{}' was not folded into the copy: {}",
                    file, ex.getMessage());
        }
        Path copy = source.resolveSibling(source.getFileName() + UPGRADE_BACKUP_INFIX
                + LocalDateTime.now().format(BACKUP_STAMP) + ".bak");
        for (int attempt = 2; Files.exists(copy); attempt++) {
            copy = source.resolveSibling(source.getFileName() + UPGRADE_BACKUP_INFIX
                    + LocalDateTime.now().format(BACKUP_STAMP) + "-" + attempt + ".bak");
        }
        Files.copy(source, copy);
        return copy;
    }

    /**
     * The file the connection's main database lives in, or {@code null} when it is not a file
     * on disk (an in-memory database, or a driver that will not say).
     */
    private static Path databaseFile(Connection connection) {
        String file = null;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA database_list")) {
            while (rs.next()) {
                if ("main".equalsIgnoreCase(rs.getString(2))) {
                    file = rs.getString(3);
                }
            }
        } catch (SQLException ex) {
            log.warn("SQLite schema: the database file could not be identified: {}", ex.getMessage());
            return null;
        }
        if (file == null || file.isBlank()) {
            return null;
        }
        Path path = Path.of(file);
        return Files.isRegularFile(path) ? path : null;
    }

    /**
     * Forgets the copies of the database that older starts made, keeping the newest
     * {@code sqlite.upgradeBackup.keep} of them. Every rebuild costs a full copy of the
     * shop's database next to the original, so without this the data folder grows by that
     * size each time the schema widens. The copy made by the running start is never a
     * candidate, because it is the one a rebuild that went wrong would be restored from.
     *
     * @param madeThisStart the backup this start wrote, or {@code null} when it wrote none
     */
    void pruneOldUpgradeBackups(Connection connection, Path madeThisStart) {
        Path database = databaseFile(connection);
        if (database == null) {
            return;
        }
        String prefix = database.getFileName() + UPGRADE_BACKUP_INFIX;
        String ownName = madeThisStart == null ? null : madeThisStart.getFileName().toString();
        List<Path> earlier;
        try (var files = Files.list(database.toAbsolutePath().getParent())) {
            earlier = files.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".bak") && !name.equals(ownName);
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            log.warn("SQLite schema: the old upgrade backups could not be listed: {}", ex.getMessage());
            return;
        }
        // One of the kept slots belongs to the copy made here, when there is one.
        int surplus = earlier.size() - Math.max(0, upgradesToKeep(connection) - (madeThisStart == null ? 0 : 1));
        for (int i = 0; i < surplus; i++) {
            Path stale = earlier.get(i);
            try {
                Files.delete(stale);
                log.info("SQLite schema: removed old upgrade backup {}", stale.getFileName());
            } catch (IOException ex) {
                log.warn("SQLite schema: the old upgrade backup {} could not be removed: {}",
                        stale.getFileName(), ex.getMessage());
            }
        }
    }

    private static int upgradesToKeep(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select setting_value from system_settings where setting_key = ?")) {
            statement.setString(1, UPGRADE_KEEP_SETTING);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Math.max(1, Integer.parseInt(rs.getString(1).trim()));
                }
            }
        } catch (SQLException | NumberFormatException ex) {
            log.debug("SQLite schema: {} is not readable, keeping {} upgrade backups",
                    UPGRADE_KEEP_SETTING, DEFAULT_UPGRADE_BACKUPS_KEPT);
        }
        return DEFAULT_UPGRADE_BACKUPS_KEPT;
    }

    /**
     * The enum-shaped checks of a {@code CREATE TABLE}: column name to the values it allows,
     * written exactly as the script writes them.
     */
    static Map<String, String> enumChecks(String createStatement) {
        Map<String, String> checks = new LinkedHashMap<>();
        Matcher matcher = ENUM_CHECK.matcher(createStatement);
        while (matcher.find()) {
            String values = matcher.group(2).trim();
            if (!enumValues(values).isEmpty()) {
                checks.put(strip(matcher.group(1)).toLowerCase(Locale.ROOT), values);
            }
        }
        return checks;
    }

    /** The members of a value list, compared without regard to case. */
    static Set<String> enumValues(String values) {
        Set<String> parsed = new LinkedHashSet<>();
        for (String value : values.split(",")) {
            String allowed = value.trim().replaceAll("^'+|'+$", "").toLowerCase(Locale.ROOT);
            if (!allowed.isEmpty()) {
                parsed.add(allowed);
            }
        }
        return parsed;
    }

    private Set<String> columnNames(Statement statement, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                names.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private Set<String> tableNames(Connection connection) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) {
                names.add(rs.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /** Table name to its columns, in script order. */
    static Map<String, Table> tables(String scriptText) {
        Map<String, Table> tables = new LinkedHashMap<>();
        for (String raw : splitStatements(withoutComments(scriptText))) {
            String statement = raw.trim();
            String lower = statement.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("create table")) {
                continue;
            }
            int open = statement.indexOf('(');
            if (open < 0) {
                throw new IllegalStateException("Unreadable CREATE TABLE statement: " + statement);
            }
            String name = table(nameBetween(statement, open));
            int close = statement.lastIndexOf(')');
            List<Column> columns = columns(statement.substring(open + 1, close));
            tables.put(name, new Table(name, columns, enumChecks(statement)));
        }
        return tables;
    }

    /** Every statement in the script, in file order, comments and blanks dropped. */
    static List<String> statements(String scriptText) {
        List<String> statements = new ArrayList<>();
        for (String part : splitStatements(withoutComments(scriptText))) {
            String ddl = part.trim();
            if (!ddl.isEmpty()) {
                statements.add(ddl);
            }
        }
        return statements;
    }

    private static String withoutComments(String scriptText) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : scriptText.split("\n")) {
            cleaned.append(line.trim().startsWith("--") ? "" : line).append('\n');
        }
        return cleaned.toString();
    }

    private static String nameBetween(String statement, int open) {
        String head = statement.substring(0, open).replace("if not exists", "").trim();
        String[] words = head.split("\\s+");
        return words[words.length - 1];
    }

    private static List<Column> columns(String body) {
        List<Column> columns = new ArrayList<>();
        for (String part : splitTopLevel(body)) {
            String clause = part.trim();
            if (clause.isEmpty()) {
                continue;
            }
            List<String> words = words(clause);
            String first = strip(words.get(0));
            if (TABLE_LEVEL_KEYWORDS.contains(first.toLowerCase(Locale.ROOT))) {
                continue;
            }
            columns.add(new Column(strip(first), declaration(words)));
        }
        return columns;
    }

    /**
     * The column as SQLite will accept it in {@code ALTER TABLE ... ADD COLUMN}: the name
     * and type, plus a constant default and its NOT NULL. UNIQUE, PRIMARY KEY, REFERENCES
     * and CHECK are refused or unwanted on an added column, and NOT NULL only survives
     * together with a default because existing rows have nothing else to hold.
     */
    private static String declaration(List<String> words) {
        StringBuilder declaration = new StringBuilder(strip(words.get(0)));
        String defaultValue = null;
        boolean notNull = false;
        int index = 1;
        while (index < words.size() && !isConstraintWord(words.get(index))) {
            declaration.append(' ').append(words.get(index));
            index++;
        }
        for (; index < words.size(); index++) {
            String word = words.get(index).toLowerCase(Locale.ROOT);
            if ("default".equals(word)) {
                if (index + 1 < words.size() && isConstantLiteral(words.get(index + 1))) {
                    defaultValue = words.get(index + 1);
                }
                index++;
            } else if ("not".equals(word) && index + 1 < words.size()
                    && "null".equals(words.get(index + 1).toLowerCase(Locale.ROOT))) {
                notNull = true;
                index++;
            }
        }
        if (defaultValue != null) {
            declaration.append(" default ").append(defaultValue);
            if (notNull) {
                declaration.append(" not null");
            }
        }
        return declaration.toString();
    }

    private static boolean isConstraintWord(String word) {
        return CONSTRAINT_KEYWORDS.contains(strip(word).toLowerCase(Locale.ROOT));
    }

    /** A number or a quoted string; CURRENT_TIMESTAMP and expressions are not constants. */
    private static boolean isConstantLiteral(String word) {
        String value = word.trim();
        return value.matches("-?\\d+(\\.\\d+)?") || (value.length() > 1 && value.startsWith("'") && value.endsWith("'"));
    }

    /** Splits a clause into words, keeping each parenthesised group in one piece. */
    static List<String> words(String clause) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLiteral = false;
        int depth = 0;
        for (int i = 0; i < clause.length(); i++) {
            char c = clause.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
            } else if (!inLiteral && c == '(') {
                depth++;
            } else if (!inLiteral && c == ')') {
                depth--;
            }
            if (!inLiteral && depth == 0 && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    words.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            words.add(current.toString());
        }
        return words;
    }

    private static String table(String rawName) {
        String name = strip(rawName).toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalStateException("Unsupported SQLite table name in schema script: " + rawName);
        }
        return name;
    }

    private static String strip(String token) {
        String cleaned = token.trim();
        return cleaned.replaceAll("^[`\\[\"]|[`\\]\"]$", "");
    }

    /** Splits on semicolons that sit outside a quoted literal or a parenthesised type. */
    static List<String> splitStatements(String scriptText) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLiteral = false;
        int depth = 0;
        for (int i = 0; i < scriptText.length(); i++) {
            char c = scriptText.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
            } else if (!inLiteral && c == '(') {
                depth++;
            } else if (!inLiteral && c == ')') {
                depth--;
            }
            if (c == ';' && !inLiteral && depth == 0) {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }

    /** Splits a CREATE TABLE body on the commas that separate its clauses. */
    static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLiteral = false;
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
            } else if (!inLiteral && c == '(') {
                depth++;
            } else if (!inLiteral && c == ')') {
                depth--;
            }
            if (c == ',' && !inLiteral && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    record Table(String name, List<Column> columns, Map<String, String> checks) {
    }

    record Column(String name, String declaration) {
    }
}
