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
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The price header at the top of Candles and Calls, checked by running its own query over seeded minutes and
 * sessions.
 */
class PriceTilesTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");
    private static final int NOW = 40;
    private static final int DETAILS = 42;
    private static final int REFRESH_LABEL = 90;
    private static final String D0 = "2026-08-25";
    private static final String D1 = "2026-08-26";
    private static final String D2 = "2026-08-27";
    private static final String D3 = "2026-08-28";

    @TempDir
    Path directory;

    private Database database;
    private long test;
    private long other;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        SqliteInstrumentRepository instruments = new SqliteInstrumentRepository(database);
        test = instruments.findOrCreate("TEST", "NSE", "CASH");
        other = instruments.findOrCreate("OTHER", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("the share price is the newest minute, its difference measured from the close of the session before")
    void latestPriceAndTodaysMove() throws Exception {
        session(test, D1, 100.0);
        settled(test, D1, 0, 99, 101, 98, 100, 900);
        live(test, D2, 0, 101, 103, 100.5, 102, 1000, ts(D2, 0) + 40);
        live(test, D2, 1, 102, 104, 99, 103, 3000, ts(D2, 1) + 25);

        Map<String, Object> now = header(D2);

        assertEquals("₹103.00", now.get("Share price"), "the close of the newest minute, still forming");
        assertEquals("+3.00 (3.00%)", now.get("Difference from close"));
        assertEquals("1D · 27 Aug", plain(now.get("Range")));
        assertEquals("₹100.00", now.get("Previous close"));
        assertEquals("₹101.00", now.get("Open"), "the first minute's open");
        assertEquals("₹104.00", now.get("High"));
        assertEquals("₹99.00", now.get("Low"));
        assertEquals("4,000", now.get("Volume"), "this session's minutes only, not the session before");
        double vwap = ((103 + 100.5 + 102) / 3.0 * 1000 + (104 + 99 + 103) / 3.0 * 3000) / 4000;
        assertEquals((103 - vwap) / vwap * 100.0, number(now, "vs VWAP"), 1e-9);
        assertEquals((ts(D2, 1) + 25) * 1000.0, number(now, "As of"), "when the last poll wrote it");
    }

    @Test
    @DisplayName("after the evening batch the session is read from the settled tape, as of the end of its last minute")
    void aSettledSession() throws Exception {
        session(test, D1, 100.0);
        session(test, D2, 98.0);
        settled(test, D2, 0, 99, 99.5, 97, 97.5, 500);
        settled(test, D2, 374, 98, 98.5, 97.8, 98, 700);

        Map<String, Object> now = header(D2);

        assertEquals("₹98.00", now.get("Share price"));
        assertEquals("-2.00 (2.00%)", now.get("Difference from close"), "a fall: the sign on the rupees, as on Groww");
        assertEquals("₹99.00", now.get("Open"));
        assertEquals("1,200", now.get("Volume"));
        assertEquals((ts(D2, 374) + 60) * 1000.0, number(now, "As of"), "15:30, the end of the 15:29 minute");
    }

    @Test
    @DisplayName("amounts are written the Indian way, with every paisa shown")
    void amountsTheIndianWay() throws Exception {
        session(test, D1, 100_000.0);
        live(test, D2, 0, 99_999.9, 1_23_456.78, 99_999.9, 1_23_456.78, 1_23_45_678, ts(D2, 0) + 30);

        Map<String, Object> now = header(D2);

        assertEquals("₹1,23,456.78", now.get("Share price"));
        assertEquals("₹1,00,000.00", now.get("Previous close"));
        assertEquals("₹99,999.90", now.get("Open"), "the trailing zero kept");
        assertEquals("+23,456.78 (23.46%)", now.get("Difference from close"));
        assertEquals("1,23,45,678", now.get("Volume"));
    }

    @Test
    @DisplayName("a difference that rounds to nothing has no sign, so it is neither red nor green")
    void aFlatDay() throws Exception {
        session(test, D1, 100.0);
        live(test, D2, 0, 100, 100.01, 99.99, 100.004, 500, ts(D2, 0) + 30);

        assertEquals("0.00 (0.00%)", header(D2).get("Difference from close"));
        assertEquals("₹100.00", header(D2).get("Share price"));
    }

    @Test
    @DisplayName("a minute both in staging and on the settled tape counts once")
    void aMinuteIsNotCountedTwice() throws Exception {
        session(test, D1, 100.0);
        settled(test, D2, 0, 100, 101, 99, 100, 500);
        live(test, D2, 0, 100, 101, 99, 100, 500, ts(D2, 0) + 60);
        live(test, D2, 1, 100, 102, 100, 101, 200, ts(D2, 1) + 30);

        assertEquals("700", header(D2).get("Volume"));
    }

    @Test
    @DisplayName("with the session before missing for this symbol the difference is from the open, not an older close")
    void aHoleIsMeasuredFromTheOpen() throws Exception {
        session(test, D0, 90.0);
        session(other, D1, 50.0);
        live(test, D2, 0, 100, 105, 99, 104, 500, ts(D2, 0) + 30);

        Map<String, Object> now = header(D2);

        assertEquals("₹104.00", now.get("Share price"));
        assertEquals("₹100.00 (open)", now.get("Previous close"),
                "D1 was a session (OTHER has it); TEST's D0 close of 90 must not stand in");
        assertEquals("+4.00 (4.00%)", now.get("Difference from close"));
    }

    @Test
    @DisplayName("no minutes in the sessions picked, or none picked, shows nothing")
    void noMinutesNoRow() throws Exception {
        session(test, D1, 100.0);
        settled(other, D2, 0, 100, 101, 99, 100, 500);

        assertNull(header(D1, D2), "TEST has no minutes in either");
        assertNull(header(), "nothing picked");
    }

    @Test
    @DisplayName("a range runs from the close before its first session to the newest price, the rest across all of it")
    void aRange() throws Exception {
        session(test, D0, 100.0);
        session(test, D1, 105.0);
        session(test, D2, 102.0);
        settled(test, D1, 0, 100, 105, 99, 104, 100);
        settled(test, D1, 1, 104, 106, 103, 105, 100);
        settled(test, D2, 0, 105, 108, 104, 107, 100);
        settled(test, D2, 1, 107, 107.5, 101, 102, 100);

        Map<String, Object> range = header(D2, D1);

        assertEquals("2D · 26\u00a0Aug →\u00a027\u00a0Aug", range.get("Range"),
                "non-breaking inside the dates, so a narrow tile never splits one");
        assertEquals("₹102.00", range.get("Share price"), "the newest minute of the sessions picked");
        assertEquals("+2.00 (2.00%)", range.get("Difference from close"), "from D0's close");
        assertEquals("₹100.00", range.get("Previous close"), "D0's close");
        assertEquals("₹100.00", range.get("Open"), "the open of the first session picked");
        assertEquals("₹108.00", range.get("High"));
        assertEquals("₹99.00", range.get("Low"));
        assertEquals("400", range.get("Volume"), "both sessions");
        double vwap = ((105 + 99 + 104) + (106 + 103 + 105) + (108 + 104 + 107) + (107.5 + 101 + 102)) / 3.0 / 4;
        assertEquals((102 - vwap) / vwap * 100.0, number(range, "vs VWAP"), 1e-9, "pooled over every minute picked");
        assertEquals((ts(D2, 1) + 60) * 1000.0, number(range, "As of"));
    }

    @Test
    @DisplayName("one session picked shows that day's move")
    void oneSession() throws Exception {
        session(test, D1, 105.0);
        settled(test, D1, 0, 100, 105, 99, 105, 100);
        settled(test, D2, 0, 105, 108, 104, 107, 100);
        settled(test, D2, 1, 107, 107.5, 101, 102, 100);

        Map<String, Object> day = header(D2);

        assertEquals("1D · 27 Aug", plain(day.get("Range")));
        assertEquals("₹105.00", day.get("Previous close"));
        assertEquals("-3.00 (2.86%)", day.get("Difference from close"));
        assertEquals("₹108.00", day.get("High"));
        assertEquals("₹101.00", day.get("Low"));
    }

    @Test
    @DisplayName("the share price follows the sessions picked: an older session shows its own last price")
    void anOlderSession() throws Exception {
        session(test, D0, 100.0);
        session(test, D1, 110.0);
        settled(test, D1, 0, 101, 112, 100, 108, 100);
        settled(test, D1, 374, 108, 111, 107, 110, 100);
        settled(test, D2, 0, 110, 150, 109, 140, 100);

        Map<String, Object> old = header(D1);

        assertEquals("₹110.00", old.get("Share price"), "D1's last minute, not the newer D2's");
        assertEquals("+10.00 (10.00%)", old.get("Difference from close"));
        assertEquals("₹112.00", old.get("High"), "D2's 150 is outside the sessions picked");
        assertEquals((ts(D1, 374) + 60) * 1000.0, number(old, "As of"));
    }

    @Test
    @DisplayName("a session left out in between moves the difference and the day count, but not the rest")
    void aSessionLeftOut() throws Exception {
        session(test, D0, 100.0);
        session(other, D2, 60.0);
        settled(test, D1, 0, 100, 103, 99, 102, 100);
        settled(test, D2, 0, 102, 200, 50, 150, 100);
        settled(test, D3, 0, 150, 151, 148, 149, 100);

        Map<String, Object> range = header(D1, D3);

        assertEquals("3D · 26 Aug → 28 Aug", plain(range.get("Range")), "D2 was a market day, though not picked");
        assertEquals("+49.00 (49.00%)", range.get("Difference from close"), "from D0's close to D3's last price");
        assertEquals("₹151.00", range.get("High"), "D2 was not picked");
        assertEquals("₹99.00", range.get("Low"), "D2 was not picked");
        assertEquals("200", range.get("Volume"), "D2 was not picked");
    }

    @Test
    @DisplayName("with no close before the first session, the range is measured from that session's open")
    void theFirstSessionStored() throws Exception {
        session(other, D0, 50.0);
        settled(test, D1, 0, 80, 81, 79, 80.5, 100);
        settled(test, D1, 1, 80.5, 90, 80, 88, 100);

        Map<String, Object> range = header(D1);

        assertEquals("₹80.00 (open)", range.get("Previous close"), "D0 was a session, but TEST has nothing before D1");
        assertEquals("+8.00 (10.00%)", range.get("Difference from close"));
    }

    @Test
    @DisplayName("a range that runs into another year names the years")
    void aRangeAcrossTheYearEnd() throws Exception {
        settled(test, "2025-12-31", 0, 100, 101, 99, 100, 100);
        settled(test, "2026-01-01", 0, 100, 101, 99, 100, 100);

        Map<String, Object> range = header("2026-01-01", "2025-12-31");

        assertEquals("2D · 31 Dec 25 → 1 Jan 26", plain(range.get("Range")));
    }

    @Test
    @DisplayName("the header is one row: the share price from the top, the figures beside it, the chart straight under")
    void layout() throws Exception {
        for (JsonNode panel : dashboard().path("panels")) {
            assertTrue(panel.path("id").asInt() != 41, "the second row of tiles is gone");
        }
        JsonNode now = panel(NOW).path("gridPos");
        JsonNode details = panel(DETAILS).path("gridPos");
        JsonNode label = panel(REFRESH_LABEL).path("gridPos");

        assertEquals(0, now.path("x").asInt());
        assertEquals(0, now.path("y").asInt(), "from the top: lower down, Grafana floats it up beside the refresh label");
        assertEquals(now.path("w").asInt(), details.path("x").asInt(), "beside it");
        assertEquals(24, details.path("x").asInt() + details.path("w").asInt(), "to the right edge");
        assertEquals(label.path("y").asInt() + label.path("h").asInt(), details.path("y").asInt(),
                "under the refresh label");
        assertTrue(label.path("x").asInt() >= details.path("x").asInt(),
                "the label sits over the figures, so they cannot float up either");
        assertEquals(bottom(now), bottom(details), "both end level");
        assertEquals(bottom(now), panel(20).path("gridPos").path("y").asInt(),
                "the minute chart follows straight after, with no gap where the second row was");

        List<JsonNode> panels = new ArrayList<>();
        dashboard().path("panels").forEach(panels::add);
        for (int a = 0; a < panels.size(); a++) {
            for (int b = a + 1; b < panels.size(); b++) {
                JsonNode p = panels.get(a).path("gridPos");
                JsonNode q = panels.get(b).path("gridPos");
                boolean apart = p.path("x").asInt() + p.path("w").asInt() <= q.path("x").asInt()
                        || q.path("x").asInt() + q.path("w").asInt() <= p.path("x").asInt()
                        || bottom(p) <= q.path("y").asInt()
                        || bottom(q) <= p.path("y").asInt();
                assertTrue(apart, "panels " + panels.get(a).path("id") + " and " + panels.get(b).path("id")
                        + " overlap");
            }
        }
    }

    @Test
    @DisplayName("between them the two panels show every figure of the query, each once")
    void theTwoPanelsShareOneQuery() throws Exception {
        JsonNode details = panel(DETAILS);
        assertEquals("-- Dashboard --", details.path("datasource").path("uid").asText());
        assertEquals(NOW, details.path("targets").get(0).path("panelId").asInt(), "the share price's own query");

        session(test, D1, 100.0);
        live(test, D2, 0, 100, 101, 99, 100.5, 500, ts(D2, 0) + 30);
        List<String> headline = new ArrayList<>();
        List<String> beside = new ArrayList<>();
        Pattern shownHere = fields(panel(NOW));
        Pattern shownBeside = fields(details);
        for (String column : header(D2).keySet()) {
            boolean here = shownHere.matcher(column).find();
            boolean there = shownBeside.matcher(column).find();
            assertTrue(here ^ there, column + " should show in exactly one of the two");
            (here ? headline : beside).add(column);
        }
        assertEquals(List.of("Share price", "Difference from close", "Range"), headline);
        assertEquals(List.of("Previous close", "Open", "High", "Low", "Volume", "vs VWAP", "As of"), beside);
    }

    @Test
    @DisplayName("a fall is red and a rise green, and a missing or flat difference is plain")
    void signColours() throws Exception {
        JsonNode mappings = property(panel(NOW), "Difference from close", "mappings");
        assertEquals("red", mapped(mappings, "-1.90 (0.14%)"));
        assertEquals("red", mapped(mappings, "-2,070.00 (7.70%)"));
        assertEquals("green", mapped(mappings, "+31.60 (2.47%)"));
        assertNull(mapped(mappings, "0.00 (0.00%)"), "flat keeps the text colour");
        JsonNode panelDefaults = panel(NOW).path("fieldConfig").path("defaults");
        assertEquals("text", panelDefaults.path("color").path("fixedColor").asText(), "the colour of a dash");

        JsonNode steps = property(panel(DETAILS), "vs VWAP", "thresholds").path("steps");
        assertEquals("text", steps.get(0).path("color").asText(), "the colour of no value");
        assertEquals("red", colour(steps, -0.01));
        assertEquals("red", colour(steps, -250_000));
        assertEquals("text", colour(steps, 0));
        assertEquals("green", colour(steps, 0.01));
    }

    /** The colour a regex value mapping gives a text, or null when none matches. */
    private static String mapped(JsonNode mappings, String text) {
        for (JsonNode mapping : mappings) {
            assertEquals("regex", mapping.path("type").asText());
            if (grafanaRegex(mapping.path("options").path("pattern").asText()).matcher(text).find()) {
                return mapping.path("options").path("result").path("color").asText();
            }
        }
        return null;
    }

    /** Grafana's reading of a pattern: "/.../" is a regex as written; anything else must match the whole text. */
    private static Pattern grafanaRegex(String pattern) {
        if (pattern.startsWith("/") && pattern.lastIndexOf('/') > 0) {
            return Pattern.compile(pattern.substring(1, pattern.lastIndexOf('/')));
        }
        return Pattern.compile("^" + pattern + "$");
    }

    private static Pattern fields(JsonNode panel) {
        return grafanaRegex(panel.path("options").path("reduceOptions").path("fields").asText());
    }

    private static JsonNode property(JsonNode panel, String field, String id) {
        for (JsonNode override : panel.path("fieldConfig").path("overrides")) {
            if (field.equals(override.path("matcher").path("options").asText())) {
                for (JsonNode property : override.path("properties")) {
                    if (id.equals(property.path("id").asText())) {
                        return property.path("value");
                    }
                }
            }
        }
        throw new AssertionError("no " + id + " for " + field + " on panel " + panel.path("id"));
    }

    private static int bottom(JsonNode gridPos) {
        return gridPos.path("y").asInt() + gridPos.path("h").asInt();
    }

    /** The colour Grafana gives a value: that of the last step at or below it. */
    private static String colour(JsonNode steps, double value) {
        String colour = steps.get(0).path("color").asText();
        for (int i = 1; i < steps.size(); i++) {
            if (value >= steps.get(i).path("value").asDouble()) {
                colour = steps.get(i).path("color").asText();
            }
        }
        return colour;
    }

    private static JsonNode dashboard() throws IOException {
        return JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
    }

    private static JsonNode panel(int id) throws IOException {
        return StreamSupport.stream(dashboard().path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == id).findFirst().orElseThrow();
    }

    private static double number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        assertNotNull(value, column);
        return ((Number) value).doubleValue();
    }

    /** The text with the non-breaking spaces the header keeps its dates together with made plain. */
    private static String plain(Object text) {
        return ((String) text).replace('\u00a0', ' ');
    }

    /** The header's one row for the sessions picked, or null when there is none. */
    private Map<String, Object> header(String... sessions) throws Exception {
        return run(sessions.length == 0 ? "''" : "'" + String.join("','", sessions) + "'");
    }

    /** The share price's query with Grafana's variables filled in; its one row, or null when there is none. */
    private Map<String, Object> run(String sessions) throws Exception {
        JsonNode target = panel(NOW).path("targets").get(0);
        String sql = target.path("queryText").asText();
        assertEquals(sql, target.path("rawQueryText").asText(), "the plugin runs queryText; the editor shows the raw");
        sql = sql.replace("$symbol", "TEST").replace("${session:sqlstring}", sessions);
        assertFalse(sql.contains("$"), "an unfilled variable would make this prove nothing: " + sql);
        String query = sql;
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(query)) {
                if (!rs.next()) {
                    return null;
                }
                ResultSetMetaData meta = rs.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 1; c <= meta.getColumnCount(); c++) {
                    row.put(meta.getColumnLabel(c), rs.getObject(c));
                }
                assertFalse(rs.next(), "one row of tiles");
                return row;
            }
        });
    }

    /** The minute's start: 09:15 IST plus the minute, as UTC epoch seconds. */
    private static long ts(String date, int minute) {
        return LocalDate.parse(date).toEpochDay() * 86_400L + 3 * 3_600L + 45 * 60L + minute * 60L;
    }

    private void session(long instrument, String date, double close) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO trading_day (instrument_id, session_date, interval_minutes, close, candle_count,
                                             source, ingested_at)
                    VALUES (?,?,1,?,375,'groww',0)""")) {
                insert.setLong(1, instrument);
                insert.setString(2, date);
                insert.setDouble(3, close);
                insert.executeUpdate();
            }
        });
    }

    /** A minute on the settled tape, written by the evening batch. */
    private void settled(long instrument, String date, int minute, double open, double high, double low, double close,
                         long volume) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO candle (instrument_id, interval_minutes, ts_epoch, open, high, low, close, volume)
                    VALUES (?,1,?,?,?,?,?,?)""")) {
                insert.setLong(1, instrument);
                insert.setLong(2, ts(date, minute));
                insert.setDouble(3, open);
                insert.setDouble(4, high);
                insert.setDouble(5, low);
                insert.setDouble(6, close);
                insert.setLong(7, volume);
                insert.executeUpdate();
            }
        });
    }

    /** A minute in staging, written by a poll of the live monitor at {@code updatedAt}. */
    private void live(long instrument, String date, int minute, double open, double high, double low, double close,
                      long volume, long updatedAt) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, open, high,
                                             low, close, volume, provisional, updated_at)
                    VALUES (?,1,?,?,?,?,?,?,?,0,?)""")) {
                insert.setLong(1, instrument);
                insert.setLong(2, ts(date, minute));
                insert.setString(3, date);
                insert.setDouble(4, open);
                insert.setDouble(5, high);
                insert.setDouble(6, low);
                insert.setDouble(7, close);
                insert.setLong(8, volume);
                insert.setLong(9, updatedAt);
                insert.executeUpdate();
            }
        });
    }
}
