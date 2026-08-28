package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.store.CandleRepository;
import com.stockanalyzer.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public final class SqliteCandleRepository implements CandleRepository {

    private final Database database;

    public SqliteCandleRepository(Database database) {
        this.database = database;
    }

    @Override
    public int saveAll(long instrumentId, int intervalMinutes, List<Candle> candles) {
        if (candles.isEmpty()) {
            return 0;
        }
        return database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO candle
                      (instrument_id, interval_minutes, ts_epoch, open, high, low, close, volume)
                    VALUES (?,?,?,?,?,?,?,?)""")) {
                for (Candle c : candles) {
                    insert.setLong(1, instrumentId);
                    insert.setInt(2, intervalMinutes);
                    insert.setLong(3, c.epochSeconds());
                    insert.setDouble(4, c.open());
                    insert.setDouble(5, c.high());
                    insert.setDouble(6, c.low());
                    insert.setDouble(7, c.close());
                    insert.setLong(8, c.volume());
                    insert.addBatch();
                }
                int written = 0;
                for (int updated : insert.executeBatch()) {
                    written += Math.max(updated, 0);
                }
                return written;
            }
        });
    }

    @Override
    public List<Candle> find(long instrumentId, int intervalMinutes, long fromTsInclusive, long toTsInclusive) {
        return database.read(connection -> {
            List<Candle> candles = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT ts_epoch, open, high, low, close, volume FROM candle
                    WHERE instrument_id = ? AND interval_minutes = ? AND ts_epoch BETWEEN ? AND ?
                    ORDER BY ts_epoch""")) {
                select.setLong(1, instrumentId);
                select.setInt(2, intervalMinutes);
                select.setLong(3, fromTsInclusive);
                select.setLong(4, toTsInclusive);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        candles.add(new Candle(rs.getLong(1), rs.getDouble(2), rs.getDouble(3),
                                rs.getDouble(4), rs.getDouble(5), rs.getLong(6)));
                    }
                }
            }
            return candles;
        });
    }
}
