package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.IngestionRunRepository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;

public final class SqliteIngestionRunRepository implements IngestionRunRepository {

    private final Database database;

    public SqliteIngestionRunRepository(Database database) {
        this.database = database;
    }

    @Override
    public long start(LocalDate sessionDate, String mode, int requested) {
        return database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO ingestion_run (started_at, session_date, mode, requested, succeeded, failed, status)
                    VALUES (?,?,?,?,0,0,'RUNNING')""", Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, JdbcSupport.now());
                insert.setString(2, sessionDate == null ? null : sessionDate.toString());
                insert.setString(3, mode);
                insert.setInt(4, requested);
                insert.executeUpdate();
                return JdbcSupport.generatedKey(insert);
            }
        });
    }

    @Override
    public void finish(long runId, int succeeded, int failed, String status) {
        database.inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE ingestion_run SET finished_at = ?, succeeded = ?, failed = ?, status = ? WHERE id = ?""")) {
                update.setLong(1, JdbcSupport.now());
                update.setInt(2, succeeded);
                update.setInt(3, failed);
                update.setString(4, status);
                update.setLong(5, runId);
                update.executeUpdate();
            }
        });
    }

    @Override
    public void recordFailure(long runId, String symbol, LocalDate sessionDate,
                              String errorType, String message, int attempts) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO ingestion_failure (run_id, symbol, session_date, error_type, message, attempts)
                    VALUES (?,?,?,?,?,?)""")) {
                insert.setLong(1, runId);
                insert.setString(2, symbol);
                insert.setString(3, sessionDate == null ? "" : sessionDate.toString());
                insert.setString(4, errorType);
                insert.setString(5, message);
                insert.setInt(6, attempts);
                insert.executeUpdate();
            }
        });
    }
}
