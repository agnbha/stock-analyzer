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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exponential moving averages on the minute chart of Candles and Calls (EMA 10, 35 and 60), checked by running the
 * panel's own query over seeded sessions and comparing with an average worked out here, the plain way, in Java: the first
 * value the average of the first N closes, then each close counting for 2 / (N + 1) of the new value.
 *
 * <p>Minute {@code i} of a session closes at {@code base + i}, so the closes are known to the cent; volumes vary from
 * minute to minute, to show they play no part.
 */
class MinuteChartEmaTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");
    private static final String DETECTOR = "topk-nonoverlap/highlow/v1";
    private static final String ALL_EMAS = "ema10,ema35,ema60";

    @TempDir
    Path directory;

    private Database database;
    private long instrument;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instrument = new SqliteInstrumentRepository(database).findOrCreate("TEST", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("the first value is the plain average of the first N closes, then each close counts for 2 / (N + 1)")
    void averageOfTheFirstNThenTheRecursion() throws Exception {
        String day = date(0);
        span(day, 0, 69, 1000);

        List<Row> rows = draw("1", ALL_EMAS, day);

        assertEquals(70, rows.size());
        assertEquals(List.of(), rows.subList(0, 9).stream().filter(r -> r.ema10() != null).toList(), "none before 10 candles");
        assertEquals(1004.5, rows.get(9).ema10(), 1e-9, "the average of 1000 to 1009");
        assertEquals(2.0 / 11 * 1010 + 9.0 / 11 * 1004.5, rows.get(10).ema10(), 1e-9);
        assertNull(rows.get(33).ema35());
        assertEquals(1017.0, rows.get(34).ema35(), 1e-9, "the average of 1000 to 1034");
        assertNull(rows.get(58).ema60());
        assertEquals(1029.5, rows.get(59).ema60(), 1e-9, "the average of 1000 to 1059");
        assertSeries(rows, closes("1", 0, 69, 1000), ALL_EMAS);
    }

    @Test
    @DisplayName("they are averages of the candles' closes, whatever the candle size: ten 5-minute candles, not ten minutes")
    void ofTheCandlesNotTheMinutes() throws Exception {
        String day = date(0);
        span(day, 0, 249, 1000);

        List<Row> fives = draw("5", ALL_EMAS, day);
        assertEquals(50, fives.size());
        assertNull(fives.get(8).ema10());
        assertEquals(1026.5, fives.get(9).ema10(), 1e-9, "the average of the first ten candles' closes, 1004 to 1049");
        assertSeries(fives, closes("5", 0, 249, 1000), ALL_EMAS);

        List<Row> tens = draw("10", ALL_EMAS, day);
        assertEquals(25, tens.size());
        assertEquals(1054.0, tens.get(9).ema10(), 1e-9, "1009, 1019 ... 1099");
        assertSeries(tens, closes("10", 0, 249, 1000), ALL_EMAS);
        assertSeries(draw("2", ALL_EMAS, day), closes("2", 0, 249, 1000), ALL_EMAS);
    }

    @Test
    @DisplayName("a session's lines carry on from where the session before left them, which is not drawn")
    void carriesOnFromTheDayBefore() throws Exception {
        String before = date(0);
        String day = date(1);
        span(before, 0, 374, 1000);
        span(day, 0, 374, 2000);

        List<Row> rows = draw("1", ALL_EMAS, day);

        assertEquals(375, rows.size(), "only the day picked is drawn");
        List<Double> both = new ArrayList<>(closes("1", 0, 374, 1000));
        both.addAll(closes("1", 0, 374, 2000));
        assertSeries(rows, both, ALL_EMAS, 375);
        double a = 2.0 / 11;
        double yesterdaysLast = ema(both, 10)[374];
        assertEquals(a * 2000 + (1 - a) * yesterdaysLast, rows.get(0).ema10(), 1e-9, "the opening candle continues yesterday's line");
        assertTrue(rows.get(0).ema10() < 1500, "nowhere near today's 2000: the average is still yesterday's: " + rows.get(0).ema10());
        assertNotNull(rows.get(0).ema35());
        assertNotNull(rows.get(0).ema60(), "no gap in the first hour, unlike an average that starts afresh each day");
    }

    @Test
    @DisplayName("a session in between that was not picked is run through, so the lines are unbroken")
    void runsThroughSessionsNotPicked() throws Exception {
        span(date(0), 0, 374, 1000);
        span(date(1), 0, 374, 2000);
        span(date(2), 0, 374, 3000);

        List<Row> rows = draw("1", ALL_EMAS, date(0), date(2));

        assertEquals(750, rows.size());
        List<Double> all = new ArrayList<>();
        for (int base : new int[]{1000, 2000, 3000}) {
            all.addAll(closes("1", 0, 374, base));
        }
        Double[] expected = ema(all, 10);
        for (int i = 0; i < 375; i++) {
            assertSame(expected[i], rows.get(i).ema10(), "first day, candle " + i);
            assertSame(expected[750 + i], rows.get(375 + i).ema10(), "third day, candle " + i);
        }
    }

    @Test
    @DisplayName("with little before it, a line starts once N candles exist, counting the days before")
    void startsOnceNCandlesExist() throws Exception {
        span(date(0), 0, 4, 1000);      // five candles the day before
        span(date(1), 0, 9, 2000);

        List<Row> rows = draw("1", "ema10", date(1));

        assertEquals(10, rows.size());
        for (int i = 0; i < 4; i++) {
            assertNull(rows.get(i).ema10(), "candle " + i + " is the " + (i + 6) + "th of the run");
        }
        assertEquals((1000 + 1001 + 1002 + 1003 + 1004 + 2000 + 2001 + 2002 + 2003 + 2004) / 10.0, rows.get(4).ema10(), 1e-9,
                "the tenth candle of the run, the fifth of the day: the plain average of all ten");
    }

    @Test
    @DisplayName("only the six sessions before the first one picked are run through, enough for the start to have faded")
    void sixSessionsOfWarmUp() throws Exception {
        for (int i = 0; i < 8; i++) {
            span(date(i), 0, 374, i < 2 ? 100_000 : 1000 + 100 * i);      // the first two are wildly different
        }
        String day = date(8);
        span(day, 0, 374, 5000);

        List<Row> rows = draw("10", ALL_EMAS, day);

        List<Double> lastSix = new ArrayList<>();
        for (int i = 2; i < 8; i++) {
            lastSix.addAll(closes("10", 0, 374, 1000 + 100 * i));
        }
        lastSix.addAll(closes("10", 0, 374, 5000));
        assertSeries(rows, lastSix, ALL_EMAS, lastSix.size() - rows.size());
    }

    @Test
    @DisplayName("each line has its own switch: VWAP, EMA 10, EMA 35, EMA 60")
    void eachLineHasItsSwitch() throws Exception {
        span(date(0), 0, 374, 1000);

        Row none = draw("1", "", date(0)).get(300);
        assertNull(none.ema10());
        assertNull(none.ema35());
        assertNull(none.ema60());
        assertNull(none.vwap());

        Row only35 = draw("1", "ema35", date(0)).get(300);
        assertNull(only35.ema10());
        assertNotNull(only35.ema35());
        assertNull(only35.ema60());
        assertNull(only35.vwap());

        Row only60 = draw("1", "ema60", date(0)).get(300);
        assertNull(only60.ema10());
        assertNull(only60.ema35());
        assertNotNull(only60.ema60());

        Row vwap = draw("1", "vwap", date(0)).get(300);
        assertNotNull(vwap.vwap());
        assertNull(vwap.ema10());

        Row junk = draw("1", "ema1,ema350,ema", date(0)).get(300);
        assertNull(junk.ema10());
        assertNull(junk.ema35());
        assertNull(junk.ema60());
    }

    @Test
    @DisplayName("volume plays no part: the same closes with other volumes give the same lines")
    void volumeDoesNotEnterTheAverage() throws Exception {
        span(date(0), 0, 374, 1000);
        List<Row> before = draw("5", ALL_EMAS, date(0));

        database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE live_candle SET volume = volume * 1000 + 7");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        List<Row> after = draw("5", ALL_EMAS, date(0));

        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).ema10(), after.get(i).ema10());
            assertEquals(before.get(i).ema35(), after.get(i).ema35());
            assertEquals(before.get(i).ema60(), after.get(i).ema60());
        }
    }

    @Test
    @DisplayName("drawn as lines: EMA 10 yellow, EMA 35 blue, EMA 60 dark red, and the simple averages are gone")
    void drawnAsLines() throws IOException {
        Map<String, Map<String, JsonNode>> byName = new LinkedHashMap<>();
        for (JsonNode override : panel().path("fieldConfig").path("overrides")) {
            Map<String, JsonNode> properties = new LinkedHashMap<>();
            override.path("properties").forEach(p -> properties.put(p.path("id").asText(), p.path("value")));
            byName.put(override.path("matcher").path("options").asText(), properties);
        }
        Map<String, String> colours = Map.of("EMA 10", "yellow", "EMA 35", "blue", "EMA 60", "dark-red");
        colours.forEach((name, colour) -> {
            Map<String, JsonNode> properties = byName.get(name);
            assertTrue(properties != null, name + " has a style");
            assertEquals("line", properties.get("custom.drawStyle").asText(), name);
            assertEquals(2, properties.get("custom.lineWidth").asInt(), name);
            assertEquals(colour, properties.get("color").path("fixedColor").asText(), name);
            assertFalse(properties.containsKey("custom.hideFrom"), name + ": named in the legend");
        });
        assertFalse(byName.containsKey("SMA 10m") || byName.containsKey("SMA 60m"));
        assertTrue(panel().path("description").asText().contains("EMA 10 / 35 / 60"));
        assertFalse(panel().path("targets").get(0).path("queryText").asText().contains("sma"));
    }

    private static JsonNode panel() throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        return StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == 20).findFirst().orElseThrow();
    }

    private record Row(long time, Double ema10, Double ema35, Double ema60, Double vwap) {
    }

    /** Every drawn candle's lines match the averages worked out here over the {@code closes}, the drawn ones the last. */
    private static void assertSeries(List<Row> rows, List<Double> closes, String picks) {
        assertSeries(rows, closes, picks, closes.size() - rows.size());
    }

    /** As above, when the first {@code skipped} closes are candles of earlier sessions that are not drawn. */
    private static void assertSeries(List<Row> rows, List<Double> closes, String picks, int skipped) {
        Double[] e10 = ema(closes, 10);
        Double[] e35 = ema(closes, 35);
        Double[] e60 = ema(closes, 60);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int at = skipped + i;
            assertSame(e10[at], row.ema10(), "EMA 10 of candle " + i);
            assertSame(e35[at], row.ema35(), "EMA 35 of candle " + i);
            assertSame(e60[at], row.ema60(), "EMA 60 of candle " + i);
        }
    }

    private static void assertSame(Double expected, Double actual, String message) {
        if (expected == null) {
            assertNull(actual, message);
        } else {
            assertNotNull(actual, message);
            assertEquals(expected, actual, 1e-9, message);
        }
    }

    /** The average the plain way: null until N closes, then their mean, then each close counting 2 / (N + 1). */
    private static Double[] ema(List<Double> closes, int n) {
        Double[] out = new Double[closes.size()];
        double alpha = 2.0 / (n + 1);
        double value = 0;
        for (int i = 0; i < closes.size(); i++) {
            if (i == n - 1) {
                value = closes.subList(0, n).stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                out[i] = value;
            } else if (i >= n) {
                value = closes.get(i) * alpha + value * (1 - alpha);
                out[i] = value;
            }
        }
        return out;
    }

    /** The closes of the candles {@code size} minutes long for minutes {@code first} to {@code last}: each its last minute's. */
    private static List<Double> closes(String size, int first, int last, int base) {
        int m = Integer.parseInt(size);
        List<Double> closes = new ArrayList<>();
        for (int slot = first / m; slot <= last / m; slot++) {
            closes.add((double) (base + Math.min(last, slot * m + m - 1)));
        }
        return closes;
    }

    private static String date(int offset) {
        return LocalDate.parse("2026-08-10").plusDays(offset).toString();
    }

    private List<Row> draw(String size, String picks, String... sessions) throws Exception {
        String sessionList = "'" + String.join("','", sessions) + "'";
        String sql = panel().path("targets").get(0).path("queryText").asText()
                .replace("${session:sqlstring}", sessionList)
                .replace("${profile:sqlstring}", "'" + AccountProfileRepository.DEFAULT_PROFILE + "'")
                .replace("$symbol", "TEST")
                .replace("$candle_size", size)
                .replace("${levels:csv}", picks)
                .replace("$detector", DETECTOR);
        assertTrue(!sql.contains("$"), "an unfilled variable would make this prove nothing: " + sql);
        return database.read(connection -> {
            List<Row> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong("time"), nullable(rs, "EMA 10"), nullable(rs, "EMA 35"),
                            nullable(rs, "EMA 60"), nullable(rs, "VWAP")));
                }
            }
            return rows;
        });
    }

    private static Double nullable(ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /** One-minute candles for minutes {@code first} to {@code last} of the session, closing at {@code base + minute}. */
    private void span(String date, int first, int last, int base) {
        long open = LocalDate.parse(date).toEpochDay() * 86_400L + 3 * 3_600L + 45 * 60L;
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, open, high,
                                             low, close, volume, provisional, updated_at)
                    VALUES (?,1,?,?,?,?,?,?,?,0,0)""")) {
                for (int i = first; i <= last; i++) {
                    double close = base + i;
                    insert.setLong(1, instrument);
                    insert.setLong(2, open + i * 60L);
                    insert.setString(3, date);
                    insert.setDouble(4, close);
                    insert.setDouble(5, close + 1);
                    insert.setDouble(6, close - 1);
                    insert.setDouble(7, close);
                    insert.setDouble(8, 500 + 37 * (i % 11));
                    insert.executeUpdate();
                }
            }
        });
    }
}
