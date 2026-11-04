package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.MarketRejectRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public final class SqliteMarketRejectRepository implements MarketRejectRepository {

    private final Database database;

    public SqliteMarketRejectRepository(Database database) {
        this.database = database;
    }

    @Override
    public List<Reject> findAll() {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT symbol, exchange, reason, first_rejected_at, last_rejected_at, times
                    FROM market_reject ORDER BY symbol, exchange""");
                 ResultSet rs = select.executeQuery()) {
                List<Reject> found = new ArrayList<>();
                while (rs.next()) {
                    found.add(new Reject(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getLong(5), rs.getInt(6)));
                }
                return found;
            }
        });
    }

    @Override
    public void record(String symbol, String exchange, String reason, long atEpoch) {
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO market_reject (symbol, exchange, reason, first_rejected_at, last_rejected_at, times)
                    VALUES (?,?,?,?,?,1)
                    ON CONFLICT (symbol, exchange) DO UPDATE SET
                      reason = excluded.reason,
                      last_rejected_at = excluded.last_rejected_at,
                      times = times + 1""")) {
                upsert.setString(1, symbol);
                upsert.setString(2, exchange);
                upsert.setString(3, reason);
                upsert.setLong(4, atEpoch);
                upsert.setLong(5, atEpoch);
                upsert.executeUpdate();
            }
        });
    }

    @Override
    public void clear(String symbol, String exchange) {
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM market_reject WHERE symbol = ? AND exchange = ?")) {
                delete.setString(1, symbol);
                delete.setString(2, exchange);
                delete.executeUpdate();
            }
        });
    }
}
