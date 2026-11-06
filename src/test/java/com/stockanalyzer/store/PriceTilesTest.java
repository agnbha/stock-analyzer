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
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two rows of price tiles at the top of Candles and Calls, checked by running each panel's own query over seeded
 * minutes and sessions.
 */
class PriceTilesTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");
    private static final int NOW = 40;
    private static final int SELECTED = 41;
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
    @DisplayName("the price is the newest minute, measured from the close of the session before")
    void latestPriceAndTodaysMove() throws Exception {
        session(test, D1, 100.0);
        settled(test, D1, 0, 99, 101, 98, 100, 900);
        live(test, D2, 0, 101, 103, 100.5, 102, 1000, ts(D2, 0) + 40);
        live(test, D2, 1, 102, 104, 99, 103, 3000, ts(D2, 1) + 25);

        Map<String, Object> now = now();

        assertEquals(103.0, number(now, "Price"), "the close of the newest minute, still forming");
        assertEquals(100.0, number(now, "Previous close"));
        assertEquals(3.0, number(now, "Change"), 1e-9);
        assertEquals(3.0, number(now, "Change %"), 1e-9);
        assertEquals(101.0, number(now, "Open"), "the first minute's open");
        assertEquals(104.0, number(now, "High"));
        assertEquals(99.0, number(now, "Low"));
        assertEquals(4000.0, number(now, "Volume"), "today's minutes only, not the session before");
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

        Map<String, Object> now = now();

        assertEquals(98.0, number(now, "Price"));
        assertEquals(-2.0, number(now, "Change"), 1e-9);
        assertEquals(-2.0, number(now, "Change %"), 1e-9);
        assertEquals(99.0, number(now, "Open"));
        assertEquals(1200.0, number(now, "Volume"));
        assertEquals((ts(D2, 374) + 60) * 1000.0, number(now, "As of"), "15:30, the end of the 15:29 minute");
    }

    @Test
    @DisplayName("a minute both in staging and on the settled tape counts once")
    void aMinuteIsNotCountedTwice() throws Exception {
        session(test, D1, 100.0);
        settled(test, D2, 0, 100, 101, 99, 100, 500);
        live(test, D2, 0, 100, 101, 99, 100, 500, ts(D2, 0) + 60);
        live(test, D2, 1, 100, 102, 100, 101, 200, ts(D2, 1) + 30);

        assertEquals(700.0, number(now(), "Volume"));
    }

    @Test
    @DisplayName("with the session before missing for this symbol there is no change, not one from an older close")
    void aHoleGivesNoChange() throws Exception {
        session(test, D0, 90.0);
        session(other, D1, 50.0);
        live(test, D2, 0, 100, 101, 99, 100, 500, ts(D2, 0) + 30);

        Map<String, Object> now = now();

        assertEquals(100.0, number(now, "Price"));
        assertNull(now.get("Previous close"), "D1 was a session (OTHER has it); TEST's D0 close must not stand in");
        assertNull(now.get("Change"));
        assertNull(now.get("Change %"));
    }

    @Test
    @DisplayName("a symbol with no minutes has no tiles")
    void noMinutesNoRow() throws Exception {
        session(test, D1, 100.0);
        settled(other, D2, 0, 100, 101, 99, 100, 500);

        assertNull(now());
        assertNull(selected(D1, D2));
    }

    @Test
    @DisplayName("a range runs from the close before its first session to the newest price, high and low within it")
    void aRange() throws Exception {
        session(test, D0, 100.0);
        session(test, D1, 105.0);
        session(test, D2, 102.0);
        settled(test, D1, 0, 100, 105, 99, 104, 100);
        settled(test, D1, 1, 104, 106, 103, 105, 100);
        settled(test, D2, 0, 105, 108, 104, 107, 100);
        settled(test, D2, 1, 107, 107.5, 101, 102, 100);

        Map<String, Object> range = selected(D2, D1);

        assertEquals("26 Aug → 27 Aug", range.get("Sessions"));
        assertEquals(100.0, number(range, "From"), "D0's close");
        assertEquals(2.0, number(range, "Change"), 1e-9);
        assertEquals(2.0, number(range, "Change %"), 1e-9);
        assertEquals(108.0, number(range, "High"));
        assertEquals(99.0, number(range, "Low"));
    }

    @Test
    @DisplayName("one session picked shows that day's move")
    void oneSession() throws Exception {
        session(test, D1, 105.0);
        settled(test, D1, 0, 100, 105, 99, 105, 100);
        settled(test, D2, 0, 105, 108, 104, 107, 100);
        settled(test, D2, 1, 107, 107.5, 101, 102, 100);

        Map<String, Object> day = selected(D2);

        assertEquals("27 Aug", day.get("Sessions"));
        assertEquals(105.0, number(day, "From"));
        assertEquals(-3.0, number(day, "Change"), 1e-9);
        assertEquals(108.0, number(day, "High"));
        assertEquals(101.0, number(day, "Low"));
    }

    @Test
    @DisplayName("a session left out in between moves the change but not the high and low")
    void aSessionLeftOut() throws Exception {
        session(test, D0, 100.0);
        settled(test, D1, 0, 100, 103, 99, 102, 100);
        settled(test, D2, 0, 102, 200, 50, 150, 100);
        settled(test, D3, 0, 150, 151, 148, 149, 100);

        Map<String, Object> range = selected(D1, D3);

        assertEquals("26 Aug → 28 Aug", range.get("Sessions"));
        assertEquals(49.0, number(range, "Change"), 1e-9, "from D0's close to D3's last price");
        assertEquals(151.0, number(range, "High"), "D2 was not picked");
        assertEquals(99.0, number(range, "Low"), "D2 was not picked");
    }

    @Test
    @DisplayName("with no close before the first session, the range is measured from that session's open")
    void theFirstSessionStored() throws Exception {
        session(other, D0, 50.0);
        settled(test, D1, 0, 80, 81, 79, 80.5, 100);
        settled(test, D1, 1, 80.5, 90, 80, 88, 100);

        Map<String, Object> range = selected(D1);

        assertEquals(80.0, number(range, "From"), "D0 was a session, but TEST has nothing before D1");
        assertEquals(8.0, number(range, "Change"), 1e-9);
        assertEquals(10.0, number(range, "Change %"), 1e-9);
    }

    @Test
    @DisplayName("a range that runs into another year names the years")
    void aRangeAcrossTheYearEnd() throws Exception {
        settled(test, "2025-12-31", 0, 100, 101, 99, 100, 100);
        settled(test, "2026-01-01", 0, 100, 101, 99, 100, 100);

        assertEquals("31 Dec 25 → 1 Jan 26", selected("2026-01-01", "2025-12-31").get("Sessions"));
    }

    @Test
    @DisplayName("the tiles sit at the top, above the minute chart, and no two panels overlap")
    void layout() throws Exception {
        JsonNode now = panel(NOW);
        JsonNode selected = panel(SELECTED);
        assertEquals(1, now.path("gridPos").path("y").asInt(), "under the one-line refresh label");
        assertEquals(24, now.path("gridPos").path("w").asInt());
        assertEquals(now.path("gridPos").path("y").asInt() + now.path("gridPos").path("h").asInt(),
                selected.path("gridPos").path("y").asInt());
        assertEquals(selected.path("gridPos").path("y").asInt() + selected.path("gridPos").path("h").asInt(),
                panel(20).path("gridPos").path("y").asInt(), "the minute chart follows straight after");

        List<JsonNode> panels = new ArrayList<>();
        dashboard().path("panels").forEach(panels::add);
        for (int a = 0; a < panels.size(); a++) {
            for (int b = a + 1; b < panels.size(); b++) {
                JsonNode p = panels.get(a).path("gridPos");
                JsonNode q = panels.get(b).path("gridPos");
                boolean apart = p.path("x").asInt() + p.path("w").asInt() <= q.path("x").asInt()
                        || q.path("x").asInt() + q.path("w").asInt() <= p.path("x").asInt()
                        || p.path("y").asInt() + p.path("h").asInt() <= q.path("y").asInt()
                        || q.path("y").asInt() + q.path("h").asInt() <= p.path("y").asInt();
                assertTrue(apart, "panels " + panels.get(a).path("id") + " and " + panels.get(b).path("id")
                        + " overlap");
            }
        }
    }

    @Test
    @DisplayName("a fall is red and a rise green, and a missing change is not painted as a fall")
    void signColours() throws Exception {
        int signed = 0;
        for (int id : new int[] {NOW, SELECTED}) {
            for (JsonNode override : panel(id).path("fieldConfig").path("overrides")) {
                for (JsonNode property : override.path("properties")) {
                    if (!"thresholds".equals(property.path("id").asText())) {
                        continue;
                    }
                    JsonNode steps = property.path("value").path("steps");
                    String field = override.path("matcher").path("options").asText();
                    assertEquals("text", steps.get(0).path("color").asText(), field + ": the colour of no value");
                    assertEquals("red", colour(steps, -0.01), field);
                    assertEquals("red", colour(steps, -250_000), field);
                    assertEquals("text", colour(steps, 0), field);
                    assertEquals("green", colour(steps, 0.01), field);
                    signed++;
                }
            }
        }
        assertEquals(5, signed, "Change and Change % in both rows, and vs VWAP");
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

    /** The first row; it also proves the tiles do not depend on the Session box, which is left unfilled. */
    private Map<String, Object> now() throws Exception {
        return run(NOW, null);
    }

    private Map<String, Object> selected(String... sessions) throws Exception {
        return run(SELECTED, "'" + String.join("','", sessions) + "'");
    }

    /** The panel's own query with Grafana's variables filled in; its one row, or null when there is none. */
    private Map<String, Object> run(int id, String sessions) throws Exception {
        JsonNode target = panel(id).path("targets").get(0);
        String sql = target.path("queryText").asText();
        assertEquals(sql, target.path("rawQueryText").asText(), "the plugin runs queryText; the editor shows the raw");
        sql = sql.replace("$symbol", "TEST");
        if (sessions != null) {
            sql = sql.replace("${session:sqlstring}", sessions);
        }
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
