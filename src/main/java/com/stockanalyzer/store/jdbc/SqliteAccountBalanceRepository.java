package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.AccountBalanceRepository;
import com.stockanalyzer.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteAccountBalanceRepository implements AccountBalanceRepository {

    private final Database database;

    public SqliteAccountBalanceRepository(Database database) {
        this.database = database;
    }

    @Override
    public void record(LocalDate sessionDate, double cash, Double invested, String source) {
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO account_balance
                      (session_date, cash, invested, total, source, recorded_at)
                    VALUES (?,?,?,?,?,?)""")) {
                upsert.setString(1, sessionDate.toString());
                upsert.setDouble(2, cash);
                JdbcSupport.setNullableDouble(upsert, 3, invested);
                JdbcSupport.setNullableDouble(upsert, 4, invested == null ? cash : cash + invested);
                upsert.setString(5, source);
                upsert.setLong(6, JdbcSupport.now());
                upsert.executeUpdate();
            }
        });
    }

    @Override
    public Optional<Balance> latestOnOrBefore(LocalDate date) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT * FROM account_balance WHERE session_date <= ? ORDER BY session_date DESC LIMIT 1")) {
                select.setString(1, date.toString());
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Balance>empty();
                }
            }
        });
    }

    @Override
    public List<Balance> findBetween(LocalDate from, LocalDate to) {
        return database.read(connection -> {
            List<Balance> balances = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT * FROM account_balance WHERE session_date BETWEEN ? AND ? ORDER BY session_date")) {
                select.setString(1, from.toString());
                select.setString(2, to.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        balances.add(map(rs));
                    }
                }
            }
            return balances;
        });
    }

    private static Balance map(ResultSet rs) throws SQLException {
        return new Balance(LocalDate.parse(rs.getString("session_date")), rs.getDouble("cash"),
                JdbcSupport.nullableDouble(rs, "invested"), JdbcSupport.nullableDouble(rs, "total"),
                rs.getString("source"));
    }
}
