package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.HotWindow;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.HotWindowRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class SqliteHotWindowRepository implements HotWindowRepository {

    private final Database database;

    public SqliteHotWindowRepository(Database database) {
        this.database = database;
    }

    @Override
    public void replaceAll(int bucketMinutes, int lookbackDays, List<HotWindow> windows) {
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM hot_window WHERE bucket_minutes = ? AND lookback_days = ?")) {
                delete.setInt(1, bucketMinutes);
                delete.setInt(2, lookbackDays);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO hot_window
                      (instrument_id, bucket_start_minute, bucket_minutes, lookback_days, hits, sessions,
                       hit_rate, hit_rate_lcb, mean_gain_pct, median_gain_pct, computed_at)
                    VALUES ((SELECT id FROM instrument WHERE symbol = ?), ?,?,?,?,?,?,?,?,?,?)""")) {
                for (HotWindow w : windows) {
                    insert.setString(1, w.symbol());
                    insert.setInt(2, w.bucketStartMinute());
                    insert.setInt(3, w.bucketMinutes());
                    insert.setInt(4, w.lookbackDays());
                    insert.setInt(5, w.hits());
                    insert.setInt(6, w.sessions());
                    insert.setDouble(7, w.hitRate());
                    insert.setDouble(8, w.hitRateLcb());
                    insert.setDouble(9, w.meanGainPct());
                    insert.setDouble(10, w.medianGainPct());
                    insert.setLong(11, JdbcSupport.now());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public List<HotWindow> find(String symbol, int bucketMinutes, int lookbackDays) {
        return database.read(connection -> {
            List<HotWindow> windows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT i.symbol AS symbol, w.* FROM hot_window w
                    LEFT JOIN instrument i ON i.id = w.instrument_id
                    WHERE w.bucket_minutes = ? AND w.lookback_days = ?
                      AND (i.symbol = ? OR w.instrument_id IS NULL)
                    ORDER BY w.hit_rate_lcb DESC""")) {
                select.setInt(1, bucketMinutes);
                select.setInt(2, lookbackDays);
                select.setString(3, symbol);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        windows.add(map(rs));
                    }
                }
            }
            return windows;
        });
    }

    @Override
    public List<HotWindow> findAll(int bucketMinutes, int lookbackDays) {
        return database.read(connection -> {
            List<HotWindow> windows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT i.symbol AS symbol, w.* FROM hot_window w
                    LEFT JOIN instrument i ON i.id = w.instrument_id
                    WHERE w.bucket_minutes = ? AND w.lookback_days = ?
                    ORDER BY w.hit_rate_lcb DESC""")) {
                select.setInt(1, bucketMinutes);
                select.setInt(2, lookbackDays);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        windows.add(map(rs));
                    }
                }
            }
            return windows;
        });
    }

    private static HotWindow map(ResultSet rs) throws SQLException {
        return new HotWindow(rs.getString("symbol"), rs.getInt("bucket_start_minute"), rs.getInt("bucket_minutes"),
                rs.getInt("lookback_days"), rs.getInt("hits"), rs.getInt("sessions"), rs.getDouble("hit_rate"),
                rs.getDouble("hit_rate_lcb"), rs.getDouble("mean_gain_pct"), rs.getDouble("median_gain_pct"));
    }
}
