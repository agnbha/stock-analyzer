package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.Week52Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

public final class SqliteWeek52Repository implements Week52Repository {

    private final Database database;

    public SqliteWeek52Repository(Database database) {
        this.database = database;
    }

    @Override
    public void upsert(long instrumentId, double high, double low, int sessions,
                       LocalDate from, LocalDate to) {
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO symbol_week52
                      (instrument_id, week52_high, week52_low, sessions, from_date, to_date, computed_at)
                    VALUES (?,?,?,?,?,?,?)""")) {
                upsert.setLong(1, instrumentId);
                upsert.setDouble(2, high);
                upsert.setDouble(3, low);
                upsert.setInt(4, sessions);
                upsert.setString(5, from == null ? null : from.toString());
                upsert.setString(6, to == null ? null : to.toString());
                upsert.setLong(7, JdbcSupport.now());
                upsert.executeUpdate();
            }
        });
    }

    @Override
    public int count() {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM symbol_week52");
                 ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}
