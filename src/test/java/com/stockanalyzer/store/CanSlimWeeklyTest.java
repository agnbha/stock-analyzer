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
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAN SLIM's chart of the symbol in daily or weekly candles, switched by the Daily and Weekly buttons that start the row
 * under it. A week is one candle at its first session (Monday to Sunday): the first open, the highest high, the lowest
 * low, the last close, the volume summed; the 50- and 200-day averages as they stood at its last session; a signal on
 * the week it fell in. The bars, scans and signals are invented.
 */
class CanSlimWeeklyTest {

    private static final Path CANSLIM = Path.of("grafana", "dashboards", "06-canslim.json");
    private static final int CHART = 20;
    private static final int ROW = 22;
    private static final Pattern BUTTON = Pattern.compile("<a href=\"([^\"]+)\"[^>]*style=\"([^\"]*)\"[^>]*>([^<]+)</a>");

    private record Bar(LocalDate date, double open, double high, double low, double close, long volume) {
    }

    @TempDir
    Path directory;

    private Database database;
    private SqliteInstrumentRepository instruments;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instruments = new SqliteInstrumentRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("a week is one candle at its first session, Monday to Sunday, a holiday short and across New Year whole")
    void weeklyCandles() {
        List<Bar> days = new ArrayList<>();
        // A week with Christmas off; the week across New Year; two days and a Sunday session; a Monday alone.
        for (String date : List.of("2025-12-22", "2025-12-23", "2025-12-24", "2025-12-26",
                "2025-12-29", "2025-12-30", "2025-12-31", "2026-01-01", "2026-01-02",
                "2026-01-05", "2026-01-06", "2026-01-11",
                "2026-01-12")) {
            int i = days.size();
            double open = 200 + 3 * Math.sin(i);
            double close = 200 + 3 * Math.cos(i * 1.7);
            days.add(new Bar(LocalDate.parse(date), open, Math.max(open, close) + 1 + i % 3,
                    Math.min(open, close) - 1 - i % 2, close, 10_000 + 1_000L * i));
        }
        store("STOCK", days);

        List<Map<String, Double>> daily = rows("STOCK", "daily");
        assertEquals(days.size(), daily.size(), "a candle a day");
        for (int i = 0; i < days.size(); i++) {
            assertCandle(days.get(i), daily.get(i), days.get(i).date().toString());
        }

        List<List<Bar>> weeks = byWeek(days);
        assertEquals(4, weeks.size());
        assertEquals(5, weeks.get(1).size(), "29 December to 2 January is one week, across New Year");
        assertEquals(LocalDate.of(2026, 1, 11), weeks.get(2).getLast().date(), "a Sunday ends its week");
        List<Map<String, Double>> weekly = rows("STOCK", "weekly");
        assertEquals(weeks.size(), weekly.size(), "a candle a week");
        for (int w = 0; w < weeks.size(); w++) {
            List<Bar> week = weeks.get(w);
            Bar candle = new Bar(week.getFirst().date(), week.getFirst().open(),
                    week.stream().mapToDouble(Bar::high).max().orElseThrow(),
                    week.stream().mapToDouble(Bar::low).min().orElseThrow(),
                    week.getLast().close(), week.stream().mapToLong(Bar::volume).sum());
            assertCandle(candle, weekly.get(w), "the week of " + week.getFirst().date());
        }

        assertEquals(daily, rows("STOCK", ""), "a frame that is neither draws days");
        assertEquals(daily, rows("STOCK", "monthly"), "a frame that is neither draws days");
    }

    @Test
    @DisplayName("the 50- and 200-day averages are the day's, and on a weekly candle as they stood at the week's last session")
    void averagesAtTheWeeksLastSession() {
        List<Bar> days = new ArrayList<>();
        List<LocalDate> dates = weekdays(LocalDate.of(2026, 1, 5), 230);
        for (int i = 0; i < dates.size(); i++) {
            days.add(new Bar(dates.get(i), 100 + i, 101 + i, 99 + i, 100 + i, 1000));
        }
        store("STOCK", days);

        List<Map<String, Double>> daily = rows("STOCK", "daily");
        for (int i = 0; i < days.size(); i++) {
            Map<String, Double> row = daily.get(i);
            if (i < 49) {
                assertNull(row.get("SMA 50"), "fewer than 50 closes on day " + i);
            } else {
                assertEquals(100 + i - 24.5, row.get("SMA 50"), 1e-9, "the last 50 closes, day " + i);
            }
            if (i < 199) {
                assertNull(row.get("SMA 200"), "fewer than 200 closes on day " + i);
            } else {
                assertEquals(100 + i - 99.5, row.get("SMA 200"), 1e-9, "the last 200 closes, day " + i);
            }
        }

        List<Map<String, Double>> weekly = rows("STOCK", "weekly");
        assertEquals(46, weekly.size());
        for (int w = 0; w < weekly.size(); w++) {
            Map<String, Double> last = daily.get(5 * w + 4);
            assertEquals(last.get("SMA 50"), weekly.get(w).get("SMA 50"), "week " + w + ": Friday's 50-day average");
            assertEquals(last.get("SMA 200"), weekly.get(w).get("SMA 200"), "week " + w + ": Friday's 200-day average");
        }
        assertNull(weekly.get(8).get("SMA 50"), "the ninth week ends on day 44: 45 closes");
        assertEquals(100 + 49 - 24.5, weekly.get(9).get("SMA 50"), 1e-9, "the tenth ends on day 49: the first average");
    }

    @Test
    @DisplayName("a buy or sell signal marks its day at the day's close, and in Weekly its week; the pivot is the last scan's")
    void signalsOnTheirWeek() {
        List<LocalDate> dates = weekdays(LocalDate.of(2026, 3, 2), 15);
        List<Bar> days = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            days.add(new Bar(dates.get(i), 300 + i, 302 + i, 299 + i, 301 + i, 5000));
        }
        store("STOCK", days);
        scan("STOCK", dates.get(1), "BUY_BREAKOUT", 301 + 1, 290);
        scan("STOCK", dates.get(3), "BUY_ZONE", 301 + 3, 290);
        scan("STOCK", dates.get(6), "HOLD", 301 + 6, 290);
        scan("STOCK", dates.get(12), "SELL_STOP_LOSS", 301 + 12, 305.5);

        List<Map<String, Double>> daily = rows("STOCK", "daily");
        for (int i = 0; i < days.size(); i++) {
            Map<String, Double> row = daily.get(i);
            assertEquals(305.5, row.get("Pivot"), "the last scan's pivot, across the chart");
            assertEquals(i == 1 || i == 3 ? Double.valueOf(301 + i) : null, row.get("Buy signal"), "buy, day " + i);
            assertEquals(i == 12 ? Double.valueOf(301 + i) : null, row.get("Sell signal"), "sell, day " + i);
        }

        List<Map<String, Double>> weekly = rows("STOCK", "weekly");
        assertEquals(3, weekly.size());
        assertEquals(304.0, weekly.get(0).get("Buy signal"), "two buys in the first week: the later, higher close");
        assertNull(weekly.get(0).get("Sell signal"));
        assertNull(weekly.get(1).get("Buy signal"), "a hold is not a signal");
        assertNull(weekly.get(1).get("Sell signal"));
        assertNull(weekly.get(2).get("Buy signal"));
        assertEquals(313.0, weekly.get(2).get("Sell signal"), "the stop on the third week, at that day's close");
        weekly.forEach(row -> assertEquals(305.5, row.get("Pivot")));
    }

    @Test
    @DisplayName("Daily and Weekly start the row under the chart, set the frame with the symbol and the time, and the one picked is marked")
    void theButtons() throws IOException {
        JsonNode dashboard = JsonMapper.INSTANCE.readTree(Files.readString(CANSLIM));
        Map<String, JsonNode> variables = new LinkedHashMap<>();
        dashboard.path("templating").path("list").forEach(v -> variables.put(v.path("name").asText(), v));
        assertEquals(List.of("symbol", "frame", "daily_look", "weekly_look"), List.copyOf(variables.keySet()),
                "the frame before the looks that read it");

        JsonNode frame = variables.get("frame");
        assertEquals("custom", frame.path("type").asText());
        assertEquals(2, frame.path("hide").asInt(), "set by the buttons, not a box at the top");
        assertFalse(frame.path("skipUrlSync").asBoolean(), "the buttons set it in the link");
        assertEquals("daily", frame.path("current").path("value").asText(), "the page opens on days");
        List<String> values = new ArrayList<>();
        frame.path("options").forEach(o -> values.add(o.path("value").asText()));
        assertEquals(List.of("daily", "weekly"), values);
        assertEquals("Daily : daily,Weekly : weekly", frame.path("query").asText());

        Map<String, String> looks = Map.of("Daily", "daily_look", "Weekly", "weekly_look");
        for (String look : looks.values()) {
            JsonNode variable = variables.get(look);
            assertEquals("query", variable.path("type").asText(), look);
            assertEquals(2, variable.path("hide").asInt(), look);
            assertTrue(variable.path("skipUrlSync").asBoolean(), look + ": CSS has no place in the link");
        }
        for (String picked : List.of("daily", "weekly", "")) {
            for (Map.Entry<String, String> look : looks.entrySet()) {
                String css = scalar(variables.get(look.getValue()).path("query").asText().replace("$frame", picked));
                boolean marked = css.contains("background-color");
                boolean shouldBe = look.getKey().equals("Weekly") == picked.equals("weekly");
                assertEquals(shouldBe, marked, look.getKey() + " with the frame '" + picked + "': " + css);
                for (String declaration : css.split(";")) {
                    if (!declaration.isBlank()) {
                        String property = declaration.substring(0, declaration.indexOf(':')).trim();
                        assertTrue(StockLinksTest.KEPT_BY_GRAFANA.contains(property), "Grafana drops " + property);
                    }
                }
            }
        }

        JsonNode row = panel(dashboard, ROW);
        Matcher button = BUTTON.matcher(row.path("options").path("content").asText());
        String page = "/d/" + dashboard.path("uid").asText() + "/";
        for (String name : List.of("Daily", "Weekly")) {
            assertTrue(button.find(), name);
            assertEquals(name, button.group(3), "Daily, then Weekly, first in the row");
            assertEquals(page + "can-slim?${symbol:queryparam}&var-frame=" + name.toLowerCase()
                    + "&${__url_time_range}", button.group(1), name + ": this page, the symbol and the time kept");
            assertTrue(button.group(2).endsWith("${" + looks.get(name) + "}"),
                    name + ": its look last, over the button's own: " + button.group(2));
        }

        JsonNode chart = panel(dashboard, CHART);
        assertTrue(chart.path("title").asText().contains("$frame candles"), chart.path("title").asText());
        assertTrue(chart.path("description").asText().contains("**Weekly**"), "the description says how");
    }

    private static void assertCandle(Bar expected, Map<String, Double> row, String what) {
        assertEquals(epoch(expected.date()), row.get("time").longValue(), what + ": at its first session");
        assertEquals(expected.open(), row.get("open"), 1e-9, what + " open");
        assertEquals(expected.high(), row.get("high"), 1e-9, what + " high");
        assertEquals(expected.low(), row.get("low"), 1e-9, what + " low");
        assertEquals(expected.close(), row.get("close"), 1e-9, what + " close");
        assertEquals(expected.volume(), row.get("volume"), 1e-9, what + " volume");
    }

    /** The bars by week, Monday to Sunday, oldest first. */
    private static List<List<Bar>> byWeek(List<Bar> days) {
        Map<LocalDate, List<Bar>> weeks = new LinkedHashMap<>();
        for (Bar day : days) {
            weeks.computeIfAbsent(day.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    monday -> new ArrayList<>()).add(day);
        }
        return new ArrayList<>(weeks.values());
    }

    private static List<LocalDate> weekdays(LocalDate from, int count) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = from; days.size() < count; day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
        }
        return days;
    }

    private void store(String symbol, List<Bar> days) {
        long instrument = instruments.findOrCreate(symbol, "NSE", "CASH");
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO daily_bar (instrument_id, session_date, ts_epoch, open, high, low, close, volume)
                    VALUES (?,?,?,?,?,?,?,?)""")) {
                for (Bar day : days) {
                    insert.setLong(1, instrument);
                    insert.setString(2, day.date().toString());
                    insert.setLong(3, epoch(day.date()));
                    insert.setDouble(4, day.open());
                    insert.setDouble(5, day.high());
                    insert.setDouble(6, day.low());
                    insert.setDouble(7, day.close());
                    insert.setLong(8, day.volume());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    /** A scan as of the day, giving the stock this signal at this close, and this pivot. */
    private void scan(String symbol, LocalDate asOf, String signal, double close, double pivot) {
        long instrument = instruments.findOrCreate(symbol, "NSE", "CASH");
        database.inTransaction(connection -> {
            long run;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO canslim_run (run_at, as_of, screened, evaluated, market_symbol, market_state)
                    VALUES (?, ?, 1, 1, 'MKT', 'CONFIRMED_UPTREND')""", Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, epoch(asOf) + 30_000);
                insert.setString(2, asOf.toString());
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    run = keys.getLong(1);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO canslim_score (run_id, instrument_id, source, as_of, close, letters, letters_passed,
                                               pivot, signal)
                    VALUES (?, ?, 'TEST', ?, ?, 'CANSLIM', 7, ?, ?)""")) {
                insert.setLong(1, run);
                insert.setLong(2, instrument);
                insert.setString(3, asOf.toString());
                insert.setDouble(4, close);
                insert.setDouble(5, pivot);
                insert.setString(6, signal);
                insert.executeUpdate();
            }
        });
    }

    private String scalar(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                assertTrue(rs.next(), sql);
                return rs.getString(1);
            }
        });
    }

    private static JsonNode panel(JsonNode dashboard, int id) {
        for (JsonNode panel : dashboard.path("panels")) {
            if (panel.path("id").asInt() == id) {
                return panel;
            }
        }
        throw new AssertionError("no panel " + id);
    }

    /** The chart's rows for the symbol in the frame given, oldest first, each column by the name the chart shows. */
    private List<Map<String, Double>> rows(String symbol, String frame) {
        String sql;
        try {
            sql = panel(JsonMapper.INSTANCE.readTree(Files.readString(CANSLIM)), CHART)
                    .path("targets").get(0).path("rawQueryText").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertNotNull(sql);
        String query = sql.replace("$symbol", symbol).replace("$frame", frame);
        return database.read(connection -> {
            List<Map<String, Double>> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(query)) {
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

    private static long epoch(LocalDate date) {
        return date.atTime(9, 15).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond();
    }
}
