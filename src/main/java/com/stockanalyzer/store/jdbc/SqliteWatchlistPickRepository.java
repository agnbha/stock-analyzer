package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.WatchlistPickRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class SqliteWatchlistPickRepository implements WatchlistPickRepository {

    private final Database database;

    public SqliteWatchlistPickRepository(Database database) {
        this.database = database;
    }

    @Override
    public List<Pick> ranking(LocalDate asOf, int limit) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT i.id, h.symbol, h.bullish_score, h.close, h.pct_above_sma20, h.ret5, h.rsi14
                    FROM v_bullish_history h
                    JOIN instrument i ON i.symbol = h.symbol
                    WHERE h.session_date = ? AND h.bullish_score IS NOT NULL
                    ORDER BY h.bullish_score DESC, h.symbol
                    LIMIT ?""")) {
                select.setString(1, asOf.toString());
                select.setInt(2, limit);
                List<Pick> picks = new ArrayList<>();
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        picks.add(new Pick(asOf, picks.size() + 1, rs.getLong("id"), rs.getString("symbol"),
                                rs.getDouble("bullish_score"), JdbcSupport.nullableDouble(rs, "close"),
                                JdbcSupport.nullableDouble(rs, "pct_above_sma20"),
                                JdbcSupport.nullableDouble(rs, "ret5"), JdbcSupport.nullableDouble(rs, "rsi14")));
                    }
                }
                return picks;
            }
        });
    }

    @Override
    public void replace(LocalDate asOf, List<Pick> picks, String source) {
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM watchlist_pick WHERE as_of_date = ?")) {
                delete.setString(1, asOf.toString());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO watchlist_pick
                      (as_of_date, rank, instrument_id, score, close, pct_above_sma20, ret5, rsi14, source, picked_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""")) {
                for (Pick pick : picks) {
                    insert.setString(1, asOf.toString());
                    insert.setInt(2, pick.rank());
                    insert.setLong(3, pick.instrumentId());
                    insert.setDouble(4, pick.score());
                    JdbcSupport.setNullableDouble(insert, 5, pick.close());
                    JdbcSupport.setNullableDouble(insert, 6, pick.pctAboveSma20());
                    JdbcSupport.setNullableDouble(insert, 7, pick.ret5());
                    JdbcSupport.setNullableDouble(insert, 8, pick.rsi14());
                    insert.setString(9, source);
                    insert.setLong(10, JdbcSupport.now());
                    insert.executeUpdate();
                }
            }
        });
    }

    @Override
    public boolean isLogged(LocalDate asOf, String source) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT 1 FROM watchlist_pick WHERE as_of_date = ? AND source = ? LIMIT 1")) {
                select.setString(1, asOf.toString());
                select.setString(2, source);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public List<LocalDate> sessionDates(LocalDate from, LocalDate to) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT DISTINCT session_date FROM v_session_move
                    WHERE session_date BETWEEN ? AND ? ORDER BY session_date""")) {
                select.setString(1, from.toString());
                select.setString(2, to.toString());
                List<LocalDate> dates = new ArrayList<>();
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        dates.add(LocalDate.parse(rs.getString(1)));
                    }
                }
                return dates;
            }
        });
    }
}
