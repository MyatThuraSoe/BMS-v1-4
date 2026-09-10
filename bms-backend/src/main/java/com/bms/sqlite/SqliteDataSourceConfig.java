package com.bms.sqlite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * SQLite-specific DataSource.
 *
 * <p>Applies only to the {@code sqlite} spring profile and builds a Hikari pool on
 * top of the raw sqlite-jdbc data source, then wraps every physical connection with
 * {@link JavaTimeNormalizingConnection} so Hibernate's {@code java.time} writes are
 * stored in the text form the driver can read back.
 */
@Configuration
@Profile("sqlite")
public class SqliteDataSourceConfig {

    @Bean
    @Primary
    public DataSource sqliteDataSource(@Value("${spring.datasource.url}") String url) {
        return new HikariSqliteDataSource(url);
    }

    static DataSource build(String url) {
        org.sqlite.SQLiteDataSource raw = new org.sqlite.SQLiteDataSource();
        raw.setUrl(url);

        DataSource wrapped = new JavaTimeNormalizingDataSource(raw);

        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setDataSource(wrapped);
        config.setPoolName("sqlite-hikari");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setConnectionInitSql("PRAGMA busy_timeout=10000");
        return new com.zaxxer.hikari.HikariDataSource(config);
    }

    static final class HikariSqliteDataSource implements DataSource {

        private final DataSource delegate;

        HikariSqliteDataSource(String url) {
            this.delegate = build(url);
        }

        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            return delegate.getConnection();
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
            return delegate.getConnection(username, password);
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws java.sql.SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws java.sql.SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws java.sql.SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws java.sql.SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }
    }
}