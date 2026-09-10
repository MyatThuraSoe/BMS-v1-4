package com.bms.sqlite;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

/**
 * JDBC pass-through wrapper that makes Hibernate's {@code java.time} bindings
 * round-trip on SQLite.
 *
 * <p>The sqlite-jdbc driver's generic {@code PreparedStatement.setObject(idx, value)}
 * falls through to {@code value.toString()}. Hibernate binds {@code LocalDateTime}
 * exactly that way, so rows are stored as ISO strings like
 * {@code "2026-09-10T12:30:00.549486"} — but the same driver's
 * {@code ResultSet.getTimestamp()} only parses {@code "yyyy-MM-dd HH:mm:ss.SSS"}
 * (it throws {@code Unparseable date: "..."} for the {@code T} form). The app
 * therefore could write a timestamp it could never read back, and cold boots
 * seeding {@code system_settings} died in a self-inflicted crash.
 *
 * <p>This wrapper intercepts {@code setObject(int, Object)} and stores all
 * java.time values in the exact {@code yyyy-MM-dd HH:mm:ss.SSS} text form the
 * driver's timestamp parser accepts. MySQL/H2 profiles do not use it.
 */
public final class JavaTimeNormalizingConnection {

    private JavaTimeNormalizingConnection() {
    }

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Wraps a raw sqlite connection so its statements normalize java.time binds. */
    public static Connection wrap(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                JavaTimeNormalizingConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler(delegate));
    }

    /**
     * Converts any supported date/time value to the exact text form sqlite-jdbc's
     * getTimestamp/getDate can parse back ({@code yyyy-MM-dd HH:mm:ss.SSS}).
     *
     * <p>Date-only values ({@code LocalDate}, {@code java.sql.Date}) must become
     * midnight datetimes ({@code 2026-09-10 00:00:00.000}) — a bare {@code 2026-09-10}
     * fails Hibernate's {@code getDate} extraction. Storing and comparing use the
     * same form, so {@code WHERE date_col = ?} still matches.
     */
    public static String toSqliteText(Object value) {
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(TIMESTAMP);
        }
        if (value instanceof LocalDate ld) {
            return ld.atStartOfDay().format(TIMESTAMP);
        }
        if (value instanceof LocalTime lt) {
            return lt.format(TIME);
        }
        if (value instanceof Instant instant) {
            return instant.atZone(ZoneOffset.UTC).format(TIMESTAMP);
        }
        if (value instanceof ZonedDateTime zdt) {
            return zdt.format(TIMESTAMP);
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.atZoneSameInstant(ZoneOffset.UTC).format(TIMESTAMP);
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().format(TIME);
        }
        if (value instanceof java.sql.Date date) {
            // java.sql.Date.toInstant() throws UnsupportedOperationException —
            // convert via its LocalDate and write midnight like LocalDate.
            return date.toLocalDate().atStartOfDay().format(TIMESTAMP);
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                    .format(TIMESTAMP);
        }
        throw new IllegalArgumentException("Unsupported temporal value: " + value);
    }

    private static final class ConnectionHandler implements InvocationHandler {

        private final Connection delegate;

        ConnectionHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("toString") && args == null) {
                return "JavaTimeNormalizingConnection[" + delegate + "]";
            }
            if (name.equals("hashCode") && args == null) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals") && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if (name.equals("close")) {
                return method.invoke(delegate, args);
            }
            // sqlite-jdbc has no LOB locators; let Hibernate's startup probe fail
            // cleanly instead of as a wrapped InvocationTargetException.
            if ((name.equals("createClob") || name.equals("createBlob") || name.equals("createNClob"))
                    && (args == null || args.length == 0)) {
                throw new java.sql.SQLFeatureNotSupportedException(
                        "SQLite does not support JDBC LOB locators: " + name);
            }
            // prepareStatement / createStatement / prepareCall -> wrap the statement.
            if (args != null && args.length >= 1 && args[0] instanceof String
                    && (name.equals("prepareStatement") || name.equals("createStatement")
                        || name.equals("prepareCall") || name.equals("nativeSQL"))) {
                Object st = method.invoke(delegate, args);
                if (st == null) {
                    return null;
                }
                if (name.equals("nativeSQL")) {
                    return st;
                }
                Class<?> iface = name.equals("prepareCall")
                        ? CallableStatement.class : PreparedStatement.class;
                return Proxy.newProxyInstance(
                        JavaTimeNormalizingConnection.class.getClassLoader(),
                        new Class<?>[]{iface, Statement.class},
                        new StatementHandler(st));
            }
            // getMetaData etc. return other JDBC objects; forward unchanged.
            return method.invoke(delegate, args);
        }
    }

    private static final class StatementHandler implements InvocationHandler {

        private final Object delegate;

        StatementHandler(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (args != null && args.length >= 2 && args[0] instanceof Integer idx) {
                Object value = args[1];
                // Normalize every java.time / legacy date bind (including the
                // 3-arg setObject(i, x, sqlType) form Hibernate uses for
                // WHERE-parameter binds) to plain TEXT that sqlite-jdbc's
                // getTimestamp/getDate/getTime can parse back.
                boolean isTemporal = value instanceof TemporalAccessor || value instanceof java.util.Date;
                boolean normalizable = name.equals("setObject")
                        && (args.length == 2 || args.length == 3) && isTemporal;
                normalizable |= (name.equals("setTimestamp") || name.equals("setDate")
                        || name.equals("setTime")) && value instanceof java.util.Date;
                if (normalizable) {
                    ((PreparedStatement) delegate).setString(idx, toSqliteText(value));
                    return null;
                }
            }
            if (name.equals("toString") && args == null) {
                return "JavaTimeNormalizingStatement[" + delegate + "]";
            }
            if (name.equals("hashCode") && args == null) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals") && args != null && args.length == 1) {
                return proxy == args[0];
            }
            return method.invoke(delegate, args);
        }
    }
}