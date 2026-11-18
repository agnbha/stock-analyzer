package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.util.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stock Trend's daily candles, by running the chart's own query: read from the daily bars kept for the whole market,
 * as CAN SLIM's chart is, so a stock outside the tracked list (no minute history) has its candles and averages too.
 * And the page's time picker, which offers nothing shorter than three months. The prices are invented.
 */
class StockTrendTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "04-stock-trend.json");
    private static final LocalDate FIRST = LocalDate.of(2026, 1, 5);
    private static final Pattern RELATIVE = Pattern.compile("now-(\\d+)([My])");

    @TempDir
    Path directory;

    private Database database;
    private long untracked;
    private long other;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        SqliteInstrumentRepository instruments = new SqliteInstrumentRepository(database);
        untracked = instruments.findOrCreate("UNTRACKED", "NSE", "CASH");
        other = instruments.findOrCreate("OTHER", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("a stock with daily bars and no minute history has its candles, and each average once its window is full")
    void candlesFromDailyBars() throws IOException {
        List<LocalDate> days = weekdays(60);
        for (int i = 0; i < days.size(); i++) {
            bar(untracked, days.get(i), 100 + i, 101 + i);
            bar(other, days.get(i), 500, 500);
        }

        List<Double[]> rows = chart("UNTRACKED");

        assertEquals(60, rows.size(), "one candle a day, and none of the other stock's");
        assertEquals(epoch(FIRST), rows.getFirst()[0].longValue(), "drawn at the session's 9:15");
        assertEquals(100.0, rows.getFirst()[1], "open");
        assertEquals(101.0, rows.getFirst()[4], "close");
        assertNull(rows.get(3)[6], "SMA 5 needs five closes");
        assertEquals((101 + 102 + 103 + 104 + 105) / 5.0, rows.get(4)[6], 1e-9);
        assertNull(rows.get(18)[7], "SMA 20 needs twenty");
        assertEquals((101 + 120) / 2.0, rows.get(19)[7], 1e-9);
        assertNull(rows.get(48)[8], "SMA 50 needs fifty");
        assertEquals((101 + 150) / 2.0, rows.get(49)[8], 1e-9);
        assertEquals((111 + 160) / 2.0, rows.get(59)[8], 1e-9, "the last fifty, not every close");
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i)[0] > rows.get(i - 1)[0], "oldest first");
        }
    }

    @Test
    @DisplayName("the time picker offers three months and longer only, and the page opens on three months")
    void noRangeShorterThanThreeMonths() throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
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

    private static int months(String from) {
        Matcher m = RELATIVE.matcher(from);
        assertTrue(m.matches(), "a range ending now and reaching back whole months or years: " + from);
        return Integer.parseInt(m.group(1)) * (m.group(2).equals("y") ? 12 : 1);
    }

    /** Each row: time, open, high, low, close, volume, SMA 5, SMA 20, SMA 50. */
    private List<Double[]> chart(String symbol) throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        String sql = null;
        for (JsonNode panel : root.path("panels")) {
            if (panel.path("id").asInt() == 10) {
                sql = panel.path("targets").get(0).path("rawQueryText").asText();
            }
        }
        String query = sql.replace("$symbol", symbol);
        return database.read(connection -> {
            List<Double[]> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(query)) {
                String[] columns = {"time", "open", "high", "low", "close", "volume", "SMA 5", "SMA 20", "SMA 50"};
                while (rs.next()) {
                    Double[] row = new Double[columns.length];
                    for (int c = 0; c < columns.length; c++) {
                        Object value = rs.getObject(columns[c]);
                        row[c] = value == null ? null : ((Number) value).doubleValue();
                    }
                    rows.add(row);
                }
            }
            return rows;
        });
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
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO daily_bar (instrument_id, session_date, ts_epoch, open, high, low, close, volume)
                    VALUES (?,?,?,?,?,?,?,1000)""")) {
                insert.setLong(1, instrument);
                insert.setString(2, date.toString());
                insert.setLong(3, epoch(date));
                insert.setDouble(4, open);
                insert.setDouble(5, Math.max(open, close) + 1);
                insert.setDouble(6, Math.min(open, close) - 1);
                insert.setDouble(7, close);
                insert.executeUpdate();
            }
        });
    }

    private static long epoch(LocalDate date) {
        return date.atTime(9, 15).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond();
    }
}
