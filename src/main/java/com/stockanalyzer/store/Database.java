package com.stockanalyzer.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the single JDBC connection to the local database and the transaction
 * boundary around it.
 *
 * <p>Fetching and analysis run on many threads; writes funnel through here, one
 * transaction at a time, guarded by a lock. That is what SQLite wants, and it
 * keeps every caller free of connection handling. WAL mode leaves concurrent
 * readers unaffected by an in-flight write.
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final Connection connection;
    private final ReentrantLock writeLock = new ReentrantLock();

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(String jdbcUrl, int busyTimeoutMillis) {
        createParentDirectory(jdbcUrl);
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl);
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
            }
            log.debug("Opened database {}", jdbcUrl);
            return new Database(connection);
        } catch (SQLException e) {
            throw new DataAccessException("Failed to open database " + jdbcUrl, e);
        }
    }

    private static void createParentDirectory(String jdbcUrl) {
        String prefix = "jdbc:sqlite:";
        if (!jdbcUrl.startsWith(prefix)) {
            return;
        }
        String file = jdbcUrl.substring(prefix.length());
        if (file.isBlank() || file.startsWith(":")) {
            return;
        }
        Path parent = Path.of(file).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (Exception e) {
            throw new DataAccessException("Cannot create database directory " + parent, e);
        }
    }

    /** Runs {@code work} against the connection with no explicit transaction. */
    public <T> T read(SqlFunction<T> work) {
        try {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new DataAccessException("Read failed: " + e.getMessage(), e);
        }
    }

    /** Runs {@code work} inside one transaction; commits on return, rolls back on any throw. */
    public <T> T inTransaction(SqlFunction<T> work) {
        writeLock.lock();
        try {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                connection.rollback();
                throw e instanceof RuntimeException re ? re
                        : new DataAccessException("Transaction failed: " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Transaction failed: " + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    public void inTransaction(SqlConsumer work) {
        inTransaction(connection -> {
            work.accept(connection);
            return null;
        });
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Failed to close database cleanly: {}", e.getMessage());
        }
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection connection) throws SQLException;
    }
}
