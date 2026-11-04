package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.AlertSeverity;
import com.stockanalyzer.store.AlertRepository;
import com.stockanalyzer.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class SqliteAlertRepository implements AlertRepository {

    private final Database database;

    public SqliteAlertRepository(Database database) {
        this.database = database;
    }

    @Override
    public boolean alreadyFired(String idempotencyKey) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT 1 FROM alert_log WHERE idempotency_key = ?")) {
                select.setString(1, idempotencyKey);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public void logFired(Alert alert) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO alert_log
                      (session_date, fired_at_epoch, symbol, rule, severity, title, message, idempotency_key)
                    VALUES (?,?,?,?,?,?,?,?)""")) {
                insert.setString(1, alert.sessionDate().toString());
                insert.setLong(2, alert.firedAtEpoch());
                insert.setString(3, alert.symbol());
                insert.setString(4, alert.rule());
                insert.setString(5, alert.severity().name());
                insert.setString(6, alert.title());
                insert.setString(7, alert.message());
                insert.setString(8, alert.idempotencyKey());
                insert.executeUpdate();
            }
        });
    }

    @Override
    public List<Alert> firedOn(LocalDate sessionDate) {
        return database.read(connection -> {
            List<Alert> rows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT session_date, fired_at_epoch, symbol, rule, severity, title, message, idempotency_key
                    FROM alert_log WHERE session_date = ? ORDER BY fired_at_epoch""")) {
                select.setString(1, sessionDate.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Alert(LocalDate.parse(rs.getString("session_date")),
                                rs.getLong("fired_at_epoch"), rs.getString("symbol"), rs.getString("rule"),
                                AlertSeverity.valueOf(rs.getString("severity")), rs.getString("title"),
                                rs.getString("message"), rs.getString("idempotency_key")));
                    }
                }
            }
            return rows;
        });
    }
}
