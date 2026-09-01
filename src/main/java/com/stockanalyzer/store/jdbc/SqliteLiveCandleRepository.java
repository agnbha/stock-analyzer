package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.LiveCandleRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;

public final class SqliteLiveCandleRepository implements LiveCandleRepository {

    private final Database database;

    public SqliteLiveCandleRepository(Database database) {
        this.database = database;
    }

    @Override
    public void upsertAll(long instrumentId, LocalDate sessionDate, int intervalMinutes,
                          List<Candle> candles, long provisionalFrom) {
        if (candles.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO live_candle
                      (instrument_id, interval_minutes, ts_epoch, session_date,
                       open, high, low, close, volume, provisional, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)""")) {
                long now = JdbcSupport.now();
                for (Candle candle : candles) {
                    upsert.setLong(1, instrumentId);
                    upsert.setInt(2, intervalMinutes);
                    upsert.setLong(3, candle.epochSeconds());
                    upsert.setString(4, sessionDate.toString());
                    upsert.setDouble(5, candle.open());
                    upsert.setDouble(6, candle.high());
                    upsert.setDouble(7, candle.low());
                    upsert.setDouble(8, candle.close());
                    upsert.setLong(9, candle.volume());
                    upsert.setInt(10, provisionalFrom > 0 && candle.epochSeconds() >= provisionalFrom ? 1 : 0);
                    upsert.setLong(11, now);
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
        });
    }

    @Override
    public int deleteConsolidated() {
        return database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM live_candle
                    WHERE EXISTS (SELECT 1 FROM candle c
                                  WHERE c.instrument_id = live_candle.instrument_id
                                    AND c.interval_minutes = live_candle.interval_minutes
                                    AND c.ts_epoch = live_candle.ts_epoch)""")) {
                return delete.executeUpdate();
            }
        });
    }

    @Override
    public int countForSession(LocalDate sessionDate) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM live_candle WHERE session_date = ?")) {
                select.setString(1, sessionDate.toString());
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }
}
