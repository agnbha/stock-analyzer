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

    /**
     * Stores the candles, the newest fetch of a minute winning over an older one. The same minute is fetched more
     * than once: when the daemon starts during the session (the minute still forming is part of that fetch), at the
     * close, and again in the evening, when the broker's tape has settled. Keeping the first copy, as this did, froze
     * a minute's half-formed high, low, close and volume for good, and the broker's later, settled copy never landed.
     * A minute the broker no longer returns is left as it was. Returns the candles added or changed.
     */
    @Override
    public int saveAll(long instrumentId, int intervalMinutes, List<Candle> candles) {
        if (candles.isEmpty()) {
            return 0;
        }
        return database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO candle
                      (instrument_id, interval_minutes, ts_epoch, open, high, low, close, volume)
                    VALUES (?,?,?,?,?,?,?,?)
                    ON CONFLICT (instrument_id, interval_minutes, ts_epoch) DO UPDATE SET
                      open = excluded.open, high = excluded.high, low = excluded.low,
                      close = excluded.close, volume = excluded.volume
                    WHERE (open, high, low, close, volume)
                          IS NOT (excluded.open, excluded.high, excluded.low, excluded.close, excluded.volume)""")) {
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
