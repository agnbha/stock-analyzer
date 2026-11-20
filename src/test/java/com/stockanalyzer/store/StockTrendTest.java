package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import com.stockanalyzer.util.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stock Trend, by running the page's own queries. Everything about the stock picked reads the daily bars kept for the
 * whole market, as CAN SLIM's chart does, so a stock outside the tracked list (no minute history) has its candles,
 * tiles and charts too; and for a tracked stock the readings are the ones the old views gave off the same sessions.
 * And the page's time picker, which offers nothing shorter than three months. The prices are invented.
 */
class StockTrendTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "04-stock-trend.json");
    private static final LocalDate FIRST = LocalDate.of(2026, 1, 5);
    private static final Pattern RELATIVE = Pattern.compile("now-(\\d+)([My])");
    private static final Pattern OTHER_SOURCE = Pattern.compile("\\b(trading_day|v_\\w+)\\b");

    @TempDir
    Path directory;

    private Database database;
    private SqliteInstrumentRepository instruments;
    private long untracked;
    private long other;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instruments = new SqliteInstrumentRepository(database);
        untracked = instruments.findOrCreate("UNTRACKED", "NSE", "CASH");
        other = instruments.findOrCreate("OTHER", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("a stock with daily bars and no minute history has its candles, and each average once its window is full")
    void candlesFromDailyBars() {
        List<LocalDate> days = weekdays(60);
        for (int i = 0; i < days.size(); i++) {
            bar(untracked, days.get(i), 100 + i, 101 + i);
            bar(other, days.get(i), 500, 500);
        }

        List<Map<String, Double>> rows = rows(10, "UNTRACKED");

        assertEquals(60, rows.size(), "one candle a day, and none of the other stock's");
        assertEquals(epoch(FIRST), rows.getFirst().get("time").longValue(), "drawn at the session's 9:15");
        assertEquals(100.0, rows.getFirst().get("open"));
        assertEquals(101.0, rows.getFirst().get("close"));
        assertNull(rows.get(3).get("SMA 5"), "SMA 5 needs five closes");
        assertEquals((101 + 102 + 103 + 104 + 105) / 5.0, rows.get(4).get("SMA 5"), 1e-9);
        assertNull(rows.get(18).get("SMA 20"), "SMA 20 needs twenty");
        assertEquals((101 + 120) / 2.0, rows.get(19).get("SMA 20"), 1e-9);
        assertNull(rows.get(48).get("SMA 50"), "SMA 50 needs fifty");
        assertEquals((101 + 150) / 2.0, rows.get(49).get("SMA 50"), 1e-9);
        assertEquals((111 + 160) / 2.0, rows.get(59).get("SMA 50"), 1e-9, "the last fifty, not every close");
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i).get("time") > rows.get(i - 1).get("time"), "oldest first");
        }
    }

    @Test
    @DisplayName("every tile and chart about the stock reads the daily bars, and fills for a stock with no minute history")
    void everyPanelAboutTheStockReadsTheDailyBars() throws IOException {
        market(300);
        series(untracked, 300, i -> 100 + 0.2 * i + 8 * Math.sin(i * 0.7));

        Set<Integer> checked = new TreeSet<>();
        for (JsonNode panel : dashboard().path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                String sql = target.path("rawQueryText").asText();
                if (!sql.contains("$symbol")) {
                    continue;
                }
                int id = panel.path("id").asInt();
                Matcher source = OTHER_SOURCE.matcher(sql);
                assertFalse(source.find(), () -> id + " reads " + source.group());
                assertTrue(sql.contains("FROM daily_bar"), id + "");
                List<Map<String, Double>> rows = rows(id, "UNTRACKED");
                assertFalse(rows.isEmpty(), id + "");
                for (String column : rows.getFirst().keySet()) {
                    assertTrue(rows.stream().anyMatch(row -> row.get(column) != null), id + ": " + column);
                }
                checked.add(id);
            }
        }
        assertEquals(Set.of(1, 2, 3, 10, 11, 13, 14), checked,
                "Trend, RSI and Beta; the candles, RSI, supports and resistances, and the range");
    }

    @Test
    @DisplayName("for a tracked stock, Trend, RSI, the pivots and the 10-day range read as the old views did")
    void sameReadingsAsTheViewsForATrackedStock() {
        long tracked = instruments.findOrCreate("TRACKED", "NSE", "CASH");
        SqliteTradingDayRepository sessions = new SqliteTradingDayRepository(database);
        List<LocalDate> days = weekdays(70);
        IntToDoubleFunction price = i -> 100 + 0.3 * i + 6 * Math.sin(i * 0.7);
        for (int i = 0; i < days.size(); i++) {
            double close = price.applyAsDouble(i);
            double open = i == 0 ? close - 1 : price.applyAsDouble(i - 1);
            double high = Math.max(open, close) + 1.5;
            double low = Math.min(open, close) - 1.2;
            bar(tracked, days.get(i), open, high, low, close);
            sessions.upsert(tracked, new DailyGainSummary("TRACKED", days.get(i), 1, open, high, low, close, 1000,
                    null, 375, epoch(days.get(i)), epoch(days.get(i)) + 22440, List.of()), "test");
        }

        assertEquals(scalar("SELECT trend_score FROM v_symbol_trend WHERE symbol = 'TRACKED' "
                + "AND trend_score IS NOT NULL ORDER BY session_date DESC LIMIT 1"), single(1, "TRACKED"), "Trend");
        assertEquals(scalar("SELECT ROUND(rsi14, 1) FROM v_symbol_trend WHERE symbol = 'TRACKED' "
                + "AND rsi14 IS NOT NULL ORDER BY session_date DESC LIMIT 1"), single(2, "TRACKED"), "RSI");
        assertSame(select("SELECT ts_epoch AS time, rsi14 AS \"RSI(14)\" FROM v_symbol_trend "
                        + "WHERE symbol = 'TRACKED' AND rsi14 IS NOT NULL ORDER BY session_date"),
                rows(11, "TRACKED"), "time", "RSI(14)");

        List<Map<String, Double>> before = select("""
                SELECT ts_epoch AS time, close AS "Close", r2 AS "R2", r1 AS "R1", pivot AS "Pivot", s1 AS "S1",
                       s2 AS "S2", high_20 AS "20-day high", low_20 AS "20-day low"
                FROM v_support_resistance WHERE symbol = 'TRACKED' ORDER BY session_date""");
        List<Map<String, Double>> levels = rows(13, "TRACKED");
        assertSame(before, levels, "time", "Close", "R2", "R1", "Pivot", "S1", "S2");
        for (int i = 0; i < levels.size(); i++) {
            for (String column : List.of("20-day high", "20-day low")) {
                near(i < 19 ? null : before.get(i).get(column), levels.get(i).get(column),
                        column + " once there are twenty sessions, row " + i);
            }
        }

        Map<Double, Double> tenDay = new HashMap<>();
        select("SELECT ts_epoch AS time, pct_of_10d_range AS pct FROM v_range_position WHERE symbol = 'TRACKED'")
                .forEach(row -> tenDay.put(row.get("time"), row.get("pct")));
        List<Map<String, Double>> range = rows(14, "TRACKED");
        assertEquals(70, range.size());
        for (Map<String, Double> row : range) {
            near(tenDay.get(row.get("time")), row.get("% of 10-day range"), "10-day range at " + row.get("time"));
            assertNull(row.get("% of 52-week range"), "seventy sessions are not a year");
        }
    }

    @Test
    @DisplayName("Beta is against the market CAN SLIM reads, over a year at most, on days both traded")
    void betaAgainstTheMarket() {
        long market = instruments.findOrCreate("MKT", "NSE", "CASH");
        scan("OTHER");
        scan("MKT");
        int gap = 280;
        double[] changes = new double[300];
        for (int i = 1; i < changes.length; i++) {
            changes[i] = i == gap ? 0.05 : i == gap + 1 ? 0.04 : 0.01 * Math.sin(i * 1.3);
        }
        List<LocalDate> days = weekdays(changes.length);
        double index = 200;
        double stock = 50;
        for (int i = 0; i < days.size(); i++) {
            index *= 1 + changes[i];
            stock *= 1 + (i < 40 ? -1 : 2) * changes[i];
            bar(market, days.get(i), index, index);
            bar(other, days.get(i), 500, 500);
            if (i != gap) {
                bar(untracked, days.get(i), stock, stock);
            }
        }

        assertEquals(2.0, single(3, "UNTRACKED"), "twice the market's every move over the last 252 days both "
                + "traded; not the earlier days that moved against it, nor the change across the day it missed, "
                + "nor the flat OTHER an earlier scan named");

        long listed = instruments.findOrCreate("NEWLISTING", "NSE", "CASH");
        int first = days.size() - 61;
        double[] prices = new double[days.size()];
        prices[first] = 80;
        for (int i = first + 1; i < days.size(); i++) {
            prices[i] = prices[i - 1] * (1 + 2 * changes[i]);
            bar(listed, days.get(i), prices[i], prices[i]);
        }
        assertNull(single(3, "NEWLISTING"), "59 days of changes");
        bar(listed, days.get(first), prices[first], prices[first]);
        assertEquals(2.0, single(3, "NEWLISTING"), "60");
    }

    @Test
    @DisplayName("the 52-week range is the last 252 sessions', from the first time there are 252")
    void fiftyTwoWeekRange() {
        int count = 300;
        double[] close = new double[count];
        double[] high = new double[count];
        double[] low = new double[count];
        List<LocalDate> days = weekdays(count);
        for (int i = 0; i < count; i++) {
            close[i] = 100 + 20 * Math.sin(i / 9.0);
            high[i] = close[i] + 2 + (i == 10 ? 80 : 0);
            low[i] = close[i] - 2;
            bar(untracked, days.get(i), close[i], high[i], low[i], close[i]);
        }

        List<Map<String, Double>> rows = rows(14, "UNTRACKED");

        assertEquals(count, rows.size());
        for (int i = 0; i < count; i++) {
            near(i < 251 ? null : position(close, high, low, i, 252), rows.get(i).get("% of 52-week range"),
                    "52-week, row " + i);
            near(i < 9 ? null : position(close, high, low, i, 10), rows.get(i).get("% of 10-day range"),
                    "10-day, row " + i);
        }
        assertTrue(rows.get(262).get("% of 52-week range") > position(close, high, low, 262, 253) + 5,
                "the spike of row 10 has left the window by row 262, 253 sessions on");
    }

    @Test
    @DisplayName("the time picker offers three months and longer only, and the page opens on three months")
    void noRangeShorterThanThreeMonths() throws IOException {
        JsonNode root = dashboard();
        List<String> offered = new ArrayList<>();
        for (JsonNode range : root.path("timepicker").path("quick_ranges")) {
            offered.add(range.path("display").asText());
            assertEquals("now", range.path("to").asText(), range.toString());
            assertTrue(months(range.path("from").asText()) >= 3, range.toString());
        }
        assertEquals(List.of("Last 3 months", "Last 6 months", "Last 1 year", "Last 2 years"), offered,
                "a list of its own replaces Grafana's, which starts at the last 5 minutes");
        assertEquals("now-3M", root.path("time").path("from").asText());
    }

    private static double position(double[] close, double[] high, double[] low, int at, int window) {
        double top = Double.NEGATIVE_INFINITY;
        double bottom = Double.POSITIVE_INFINITY;
        for (int i = at - window + 1; i <= at; i++) {
            top = Math.max(top, high[i]);
            bottom = Math.min(bottom, low[i]);
        }
        return (close[at] - bottom) / (top - bottom) * 100.0;
    }

    private static int months(String from) {
        Matcher m = RELATIVE.matcher(from);
        assertTrue(m.matches(), "a range ending now and reaching back whole months or years: " + from);
        return Integer.parseInt(m.group(1)) * (m.group(2).equals("y") ? 12 : 1);
    }

    private static void assertSame(List<Map<String, Double>> expected, List<Map<String, Double>> actual,
                                   String... columns) {
        assertEquals(expected.size(), actual.size(), "rows");
        for (int i = 0; i < expected.size(); i++) {
            for (String column : columns) {
                near(expected.get(i).get(column), actual.get(i).get(column), column + ", row " + i);
            }
        }
    }

    private static void near(Double expected, Double actual, String message) {
        if (expected == null) {
            assertNull(actual, message);
        } else {
            assertTrue(actual != null, message + ": expected " + expected);
            assertEquals(expected, actual, 1e-9, message);
        }
    }

    private static JsonNode dashboard() throws IOException {
        return JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
    }

    /** The panel's rows for the symbol, each column by the name the chart shows. */
    private List<Map<String, Double>> rows(int panel, String symbol) {
        try {
            for (JsonNode p : dashboard().path("panels")) {
                if (p.path("id").asInt() == panel) {
                    return select(p.path("targets").get(0).path("rawQueryText").asText().replace("$symbol", symbol));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new AssertionError("no panel " + panel);
    }

    /** A tile's one value, or null when it has none. */
    private Double single(int panel, String symbol) {
        List<Map<String, Double>> rows = rows(panel, symbol);
        assertTrue(rows.size() <= 1, panel + " is a tile");
        return rows.isEmpty() ? null : rows.getFirst().values().iterator().next();
    }

    private Double scalar(String sql) {
        List<Map<String, Double>> rows = select(sql);
        return rows.isEmpty() ? null : rows.getFirst().values().iterator().next();
    }

    private List<Map<String, Double>> select(String sql) {
        return database.read(connection -> {
            List<Map<String, Double>> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData columns = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Double> row = new LinkedHashMap<>();
                    for (int c = 1; c <= columns.getColumnCount(); c++) {
                        Object value = rs.getObject(c);
                        row.put(columns.getColumnLabel(c), value == null ? null : ((Number) value).doubleValue());
                    }
                    rows.add(row);
                }
            }
            return rows;
        });
    }

    /** The market CAN SLIM read, MKT, over the sessions given, as the latest scan names it. */
    private void market(int sessions) {
        long market = instruments.findOrCreate("MKT", "NSE", "CASH");
        series(market, sessions, i -> 200 + 10 * Math.sin(i * 1.3) + 0.1 * i);
        scan("MKT");
    }

    private void scan(String marketSymbol) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO canslim_run (run_at, screened, evaluated, market_symbol, market_state)
                    VALUES (1800000000, 0, 0, ?, 'CONFIRMED_UPTREND')""")) {
                insert.setString(1, marketSymbol);
                insert.executeUpdate();
            }
        });
    }

    /**
     * Each session opening at the last close, its high and low a rupee either side, and its volume swinging about a
     * million shares, wider and wider, so that each new swing is the heaviest and the lightest yet: with swings of the
     * price of 5% and more, every color of the volume bars shows.
     */
    private void series(long instrument, int sessions, IntToDoubleFunction close) {
        List<LocalDate> days = weekdays(sessions);
        for (int i = 0; i < sessions; i++) {
            double open = close.applyAsDouble(Math.max(0, i - 1));
            double last = close.applyAsDouble(i);
            long volume = Math.round(1_000_000 + 200_000 * Math.sin(i * 0.7) * (1 + i / 100.0));
            bar(instrument, days.get(i), open, Math.max(open, last) + 1, Math.min(open, last) - 1, last, volume);
        }
    }

    private static List<LocalDate> weekdays(int count) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = FIRST; days.size() < count; day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
        }
        return days;
    }

    private void bar(long instrument, LocalDate date, double open, double close) {
        bar(instrument, date, open, Math.max(open, close) + 1, Math.min(open, close) - 1, close);
    }

    private void bar(long instrument, LocalDate date, double open, double high, double low, double close) {
        bar(instrument, date, open, high, low, close, 1000);
    }

    private void bar(long instrument, LocalDate date, double open, double high, double low, double close,
                     long volume) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO daily_bar (instrument_id, session_date, ts_epoch, open, high, low, close, volume)
                    VALUES (?,?,?,?,?,?,?,?)""")) {
                insert.setLong(1, instrument);
                insert.setString(2, date.toString());
                insert.setLong(3, epoch(date));
                insert.setDouble(4, open);
                insert.setDouble(5, high);
                insert.setDouble(6, low);
                insert.setDouble(7, close);
                insert.setLong(8, volume);
                insert.executeUpdate();
            }
        });
    }

    private static long epoch(LocalDate date) {
        return date.atTime(9, 15).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond();
    }
}
