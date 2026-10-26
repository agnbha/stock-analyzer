package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.canslim.DailyBar;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.DailyBarRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

public final class SqliteDailyBarRepository implements DailyBarRepository {

    private final Database database;

    public SqliteDailyBarRepository(Database database) {
        this.database = database;
    }

    @Override
    public void upsert(long instrumentId, List<DailyBar> bars, ToLongFunction<DailyBar> tsEpoch) {
        if (bars.isEmpty()) {
            return;
        }
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO daily_bar
                      (instrument_id, session_date, ts_epoch, open, high, low, close, volume)
                    VALUES (?,?,?,?,?,?,?,?)""")) {
                for (DailyBar bar : bars) {
                    upsert.setLong(1, instrumentId);
                    upsert.setString(2, bar.date().toString());
                    upsert.setLong(3, tsEpoch.applyAsLong(bar));
                    upsert.setDouble(4, bar.open());
                    upsert.setDouble(5, bar.high());
                    upsert.setDouble(6, bar.low());
                    upsert.setDouble(7, bar.close());
                    upsert.setLong(8, bar.volume());
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
        });
    }

    @Override
    public List<DailyBar> find(long instrumentId) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT session_date, open, high, low, close, volume FROM daily_bar
                    WHERE instrument_id = ? ORDER BY session_date""")) {
                select.setLong(1, instrumentId);
                List<DailyBar> bars = new ArrayList<>();
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        bars.add(new DailyBar(JdbcSupport.date(rs, "session_date"), rs.getDouble("open"),
                                rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("close"),
                                rs.getLong("volume")));
                    }
                }
                return bars;
            }
        });
    }
}
