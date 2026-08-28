package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TradingDayRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class SqliteTradingDayRepository implements TradingDayRepository {

    private final Database database;

    public SqliteTradingDayRepository(Database database) {
        this.database = database;
    }

    @Override
    public long upsert(long instrumentId, DailyGainSummary s, String source) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO trading_day
                      (instrument_id, session_date, interval_minutes, open, high, low, close, volume,
                       day_change_pct, candle_count, first_candle_ts, last_candle_ts, source, ingested_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (instrument_id, session_date, interval_minutes) DO UPDATE SET
                      open = excluded.open, high = excluded.high, low = excluded.low, close = excluded.close,
                      volume = excluded.volume, day_change_pct = excluded.day_change_pct,
                      candle_count = excluded.candle_count, first_candle_ts = excluded.first_candle_ts,
                      last_candle_ts = excluded.last_candle_ts, source = excluded.source,
                      ingested_at = excluded.ingested_at""")) {
                ps.setLong(1, instrumentId);
                ps.setString(2, s.sessionDate().toString());
                ps.setInt(3, s.intervalMinutes());
                ps.setDouble(4, s.open());
                ps.setDouble(5, s.high());
                ps.setDouble(6, s.low());
                ps.setDouble(7, s.close());
                ps.setLong(8, s.volume());
                JdbcSupport.setNullableDouble(ps, 9, s.dayChangePct());
                ps.setInt(10, s.candleCount());
                ps.setLong(11, s.firstCandleTs());
                ps.setLong(12, s.lastCandleTs());
                ps.setString(13, source);
                ps.setLong(14, JdbcSupport.now());
                ps.executeUpdate();
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM trading_day WHERE instrument_id = ? AND session_date = ? AND interval_minutes = ?")) {
                select.setLong(1, instrumentId);
                select.setString(2, s.sessionDate().toString());
                select.setInt(3, s.intervalMinutes());
                try (ResultSet rs = select.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Override
    public Optional<Long> findId(long instrumentId, LocalDate sessionDate, int intervalMinutes) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM trading_day WHERE instrument_id = ? AND session_date = ? AND interval_minutes = ?")) {
                select.setLong(1, instrumentId);
                select.setString(2, sessionDate.toString());
                select.setInt(3, intervalMinutes);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getLong(1)) : Optional.<Long>empty();
                }
            }
        });
    }

    @Override
    public Set<LocalDate> storedSessionDates(long instrumentId, int intervalMinutes, LocalDate from, LocalDate to) {
        return database.read(connection -> {
            Set<LocalDate> dates = new LinkedHashSet<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT session_date FROM trading_day
                    WHERE instrument_id = ? AND interval_minutes = ? AND session_date BETWEEN ? AND ?""")) {
                select.setLong(1, instrumentId);
                select.setInt(2, intervalMinutes);
                select.setString(3, from.toString());
                select.setString(4, to.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        dates.add(LocalDate.parse(rs.getString(1)));
                    }
                }
            }
            return dates;
        });
    }

    @Override
    public Optional<DailyGainSummary> find(long instrumentId, LocalDate sessionDate, int intervalMinutes) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT t.*, i.symbol FROM trading_day t JOIN instrument i ON i.id = t.instrument_id
                    WHERE t.instrument_id = ? AND t.session_date = ? AND t.interval_minutes = ?""")) {
                select.setLong(1, instrumentId);
                select.setString(2, sessionDate.toString());
                select.setInt(3, intervalMinutes);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<DailyGainSummary>empty();
                }
            }
        });
    }

    @Override
    public List<DailyGainSummary> findRange(long instrumentId, int intervalMinutes, LocalDate from, LocalDate to) {
        return database.read(connection -> {
            List<DailyGainSummary> rows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT t.*, i.symbol FROM trading_day t JOIN instrument i ON i.id = t.instrument_id
                    WHERE t.instrument_id = ? AND t.interval_minutes = ? AND t.session_date BETWEEN ? AND ?
                    ORDER BY t.session_date""")) {
                select.setLong(1, instrumentId);
                select.setInt(2, intervalMinutes);
                select.setString(3, from.toString());
                select.setString(4, to.toString());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        rows.add(map(rs));
                    }
                }
            }
            return rows;
        });
    }

    @Override
    public Optional<Double> previousClose(long instrumentId, LocalDate sessionDate, int intervalMinutes) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT close FROM trading_day
                    WHERE instrument_id = ? AND interval_minutes = ? AND session_date < ?
                    ORDER BY session_date DESC LIMIT 1""")) {
                select.setLong(1, instrumentId);
                select.setInt(2, intervalMinutes);
                select.setString(3, sessionDate.toString());
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getDouble(1)) : Optional.<Double>empty();
                }
            }
        });
    }

    @Override
    public Optional<Double> latestCloseOnOrBefore(long instrumentId, LocalDate date) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT close FROM trading_day WHERE instrument_id = ? AND session_date <= ?
                    ORDER BY session_date DESC LIMIT 1""")) {
                select.setLong(1, instrumentId);
                select.setString(2, date.toString());
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getDouble(1)) : Optional.<Double>empty();
                }
            }
        });
    }

    @Override
    public int countSessions(LocalDate from, LocalDate to) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(DISTINCT session_date) FROM trading_day WHERE session_date BETWEEN ? AND ?")) {
                select.setString(1, from.toString());
                select.setString(2, to.toString());
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private static DailyGainSummary map(ResultSet rs) throws SQLException {
        return new DailyGainSummary(
                rs.getString("symbol"),
                LocalDate.parse(rs.getString("session_date")),
                rs.getInt("interval_minutes"),
                rs.getDouble("open"), rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("close"),
                rs.getLong("volume"),
                JdbcSupport.nullableDouble(rs, "day_change_pct"),
                rs.getInt("candle_count"),
                rs.getLong("first_candle_ts"),
                rs.getLong("last_candle_ts"),
                List.of());
    }
}
