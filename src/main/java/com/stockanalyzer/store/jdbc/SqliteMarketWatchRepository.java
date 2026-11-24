package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.MarketWatchRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SqliteMarketWatchRepository implements MarketWatchRepository {

    private final Database database;

    public SqliteMarketWatchRepository(Database database) {
        this.database = database;
    }

    @Override
    public void replace(String index, int position, List<String> symbols, String exchange, String source, long nowEpoch) {
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM index_member WHERE index_name = ?")) {
                delete.setString(1, index);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO index_member (index_name, symbol, exchange, index_position, source, fetched_at)
                    VALUES (?,?,?,?,?,?)""")) {
                for (String symbol : symbols) {
                    insert.setString(1, index);
                    insert.setString(2, symbol);
                    insert.setString(3, exchange);
                    insert.setInt(4, position);
                    insert.setString(5, source);
                    insert.setLong(6, nowEpoch);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public void reorder(String index, int position) {
        database.inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE index_member SET index_position = ? WHERE index_name = ?")) {
                update.setInt(1, position);
                update.setString(2, index);
                update.executeUpdate();
            }
        });
    }

    @Override
    public int retainOnly(Collection<String> indices) {
        Set<String> keep = Set.copyOf(indices);
        int[] dropped = {0};
        database.inTransaction(connection -> {
            List<String> held = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT DISTINCT index_name FROM index_member");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    held.add(rs.getString(1));
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM index_member WHERE index_name = ?")) {
                for (String index : held) {
                    if (!keep.contains(index)) {
                        delete.setString(1, index);
                        delete.executeUpdate();
                        dropped[0]++;
                    }
                }
            }
        });
        return dropped[0];
    }

    @Override
    public Map<String, Integer> counts() {
        return database.read(connection -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT index_name, COUNT(*) FROM index_member GROUP BY index_name
                    ORDER BY MIN(index_position), index_name""");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getInt(2));
                }
            }
            return counts;
        });
    }

    @Override
    public List<BarSpan> barSpans(String segment) {
        return database.read(connection -> {
            List<BarSpan> spans = new ArrayList<>();
            // A stock of the market is one with a bar in the ten days to the latest session: listed, and priced by the
            // nightly scan. Its first and last bar come from the primary key, one seek each.
            try (PreparedStatement select = connection.prepareStatement("""
                    WITH latest AS (SELECT MAX(session_date) AS last_day FROM daily_bar),
                    wanted AS (
                      SELECT symbol, exchange, 1 AS member FROM index_member
                      UNION ALL
                      SELECT i.symbol, i.exchange, 0
                      FROM instrument i CROSS JOIN latest
                      WHERE i.segment = ?
                        AND EXISTS (SELECT 1 FROM daily_bar b WHERE b.instrument_id = i.id
                                    AND b.session_date >= date(latest.last_day, '-10 days'))
                    ),
                    stocks AS (SELECT symbol, exchange, MAX(member) AS member FROM wanted GROUP BY symbol, exchange)
                    SELECT s.symbol, s.exchange, s.member,
                           (SELECT MIN(b.session_date) FROM daily_bar b WHERE b.instrument_id = i.id),
                           (SELECT MAX(b.session_date) FROM daily_bar b WHERE b.instrument_id = i.id),
                           r.asked_from
                    FROM stocks s
                    LEFT JOIN instrument i ON i.symbol = s.symbol AND i.exchange = s.exchange AND i.segment = ?
                    LEFT JOIN daily_bar_reach r ON r.instrument_id = i.id
                    ORDER BY s.member DESC, s.symbol, s.exchange""")) {
                select.setString(1, segment);
                select.setString(2, segment);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        spans.add(new BarSpan(rs.getString(1), rs.getString(2), rs.getInt(3) == 1, date(rs.getString(4)),
                                date(rs.getString(5)), date(rs.getString(6))));
                    }
                }
            }
            return spans;
        });
    }

    @Override
    public void markAsked(long instrumentId, LocalDate from, long nowEpoch) {
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO daily_bar_reach (instrument_id, asked_from, asked_at) VALUES (?,?,?)
                    ON CONFLICT (instrument_id) DO UPDATE SET
                      asked_from = MIN(daily_bar_reach.asked_from, excluded.asked_from), asked_at = excluded.asked_at""")) {
                upsert.setLong(1, instrumentId);
                upsert.setString(2, from.toString());
                upsert.setLong(3, nowEpoch);
                upsert.executeUpdate();
            }
        });
    }

    @Override
    public Optional<LocalDate> latestSession() {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT MAX(session_date) FROM daily_bar");
                 ResultSet rs = select.executeQuery()) {
                return Optional.ofNullable(rs.next() ? date(rs.getString(1)) : null);
            }
        });
    }

    /**
     * One pass over every stock's bars with four moving averages beside each close, counted a session at a time, then
     * written as a row a session and average. A stock is counted for an average once it has that many sessions. A close
     * equal to its average (a stock that has not moved) is neither above nor below it: the difference is rounded to six
     * places first, so a float's last digit does not decide.
     */
    @Override
    public int rebuildBreadth(String segment, long nowEpoch) {
        int[] written = {0};
        database.inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM market_breadth")) {
                delete.executeUpdate();
            }
            StringBuilder sql = new StringBuilder("""
                    WITH averaged AS (
                      SELECT b.session_date, b.ts_epoch, b.close""");
            for (int days : BREADTH_AVERAGES) {
                sql.append(",\n         AVG(b.close) OVER w").append(days).append(" AS sma").append(days)
                        .append(", COUNT(*) OVER w").append(days).append(" AS n").append(days);
            }
            sql.append("""

                      FROM daily_bar b
                      JOIN instrument i ON i.id = b.instrument_id AND i.segment = ?
                      WHERE b.close IS NOT NULL
                      WINDOW\s""");
            List<String> windows = new ArrayList<>();
            for (int days : BREADTH_AVERAGES) {
                windows.add("w" + days + " AS (PARTITION BY b.instrument_id ORDER BY b.session_date ROWS BETWEEN "
                        + (days - 1) + " PRECEDING AND CURRENT ROW)");
            }
            sql.append(String.join(",\n             ", windows)).append("""

                    ),
                    days AS MATERIALIZED (
                      SELECT session_date, MIN(ts_epoch) AS ts, COUNT(*) AS stocks""");
            for (int days : BREADTH_AVERAGES) {
                sql.append(",\n         SUM(n").append(days).append(" = ").append(days).append(" AND ROUND(close - sma").append(days).append(", 6) > 0")
                        .append(") AS above").append(days)
                        .append(", SUM(n").append(days).append(" = ").append(days).append(" AND ROUND(close - sma").append(days).append(", 6) < 0")
                        .append(") AS below").append(days)
                        .append(", SUM(n").append(days).append(" = ").append(days).append(") AS counted").append(days);
            }
            sql.append("""

                      FROM averaged
                      GROUP BY session_date
                    )
                    INSERT INTO market_breadth (session_date, sma_days, above, below, counted, stocks, ts_epoch, computed_at)
                    """);
            List<String> selects = new ArrayList<>();
            for (int days : BREADTH_AVERAGES) {
                selects.add("SELECT session_date, " + days + ", above" + days + ", below" + days + ", counted" + days
                        + ", stocks, ts, ? FROM days WHERE counted" + days + " > 0");
            }
            sql.append(String.join("\nUNION ALL\n", selects));
            try (PreparedStatement insert = connection.prepareStatement(sql.toString())) {
                insert.setString(1, segment);
                for (int i = 0; i < BREADTH_AVERAGES.size(); i++) {
                    insert.setLong(2 + i, nowEpoch);
                }
                written[0] = insert.executeUpdate();
            }
        });
        return written[0];
    }

    @Override
    public void upsertIndexBars(String index, List<IndexBar> bars, long nowEpoch) {
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO index_bar (index_name, session_date, ts_epoch, open, high, low, close, rsi14,
                                                      fetched_at)
                    VALUES (?,?,?,?,?,?,?,?,?)""")) {
                for (IndexBar bar : bars) {
                    upsert.setString(1, index);
                    upsert.setString(2, bar.date().toString());
                    upsert.setLong(3, bar.tsEpoch());
                    upsert.setDouble(4, bar.open());
                    upsert.setDouble(5, bar.high());
                    upsert.setDouble(6, bar.low());
                    upsert.setDouble(7, bar.close());
                    if (bar.rsi14() == null) {
                        upsert.setNull(8, java.sql.Types.REAL);
                    } else {
                        upsert.setDouble(8, bar.rsi14());
                    }
                    upsert.setLong(9, nowEpoch);
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
        });
    }

    private static LocalDate date(String text) {
        return text == null ? null : LocalDate.parse(text);
    }
}
