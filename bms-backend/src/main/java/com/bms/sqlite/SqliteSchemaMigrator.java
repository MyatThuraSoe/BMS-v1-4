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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * of the entity model and is what {@code ddl-auto: update} used to run.
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
            // Indexes come last: a shop whose tables predate a column cannot have that
            // column's index created until the column itself has been added.
            addMissingColumns(connection, expected, alreadyCreated);
            execute(connection, indexes);
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
            tables.put(name, new Table(name, columns));
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

    record Table(String name, List<Column> columns) {
    }

    record Column(String name, String declaration) {
    }
}
