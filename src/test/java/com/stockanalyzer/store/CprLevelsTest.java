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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CPR (central pivot range) levels on the minute-candle panel of Candles and Calls, checked by running the panel's
 * own query over seeded sessions: the arithmetic against levels worked out by hand from the formulas on the slide, the
 * session each set of levels comes from, and the box that draws fewer of them. The prices are invented.
 */
class CprLevelsTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");
    private static final String D0 = "2026-08-25";
    private static final String D1 = "2026-08-26";
    private static final String D2 = "2026-08-27";
    private static final String D3 = "2026-08-28";
    private static final List<String> LEVELS = List.of("R4", "R3", "R2", "R1", "TC", "Pivot", "BC", "S1", "S2", "S3", "S4");

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
    @DisplayName("the levels are the slide's formulas applied to the High, Low and Close of the session before")
    void theSlidesFormulas() throws Exception {
        // High 120, Low 90, Close 108: Pivot (120+90+108)/3 = 106, BC (120+90)/2 = 105, so TC = (106-105)+106 = 107.
        // R1 = 2x106-90 = 122, R2 = 106+30 = 136, R3 = 122+30 = 152, R4 = 152+(136-122) = 166.
        // S1 = 2x106-120 = 92, S2 = 106-30 = 76, S3 = 92-30 = 62, S4 = 62-(92-76) = 46.
        session(test, D1, 120.0, 90.0, 108.0);
        candles(test, D2);

        Map<String, Double> first = points("all", D2).getFirst().levels();

        assertEquals(Map.ofEntries(Map.entry("R4", 166.0), Map.entry("R3", 152.0), Map.entry("R2", 136.0),
                Map.entry("R1", 122.0), Map.entry("TC", 107.0), Map.entry("Pivot", 106.0), Map.entry("BC", 105.0),
                Map.entry("S1", 92.0), Map.entry("S2", 76.0), Map.entry("S3", 62.0), Map.entry("S4", 46.0)), first);
    }

    @Test
    @DisplayName("S2 is the pivot less the day's range, as R2 is the pivot plus it: the slide's (High + Low) is a slip")
    void s2IsPivotLessTheRange() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        candles(test, D2);

        double s2 = points("all", D2).getFirst().levels().get("S2");

        assertEquals(106.0 - (120.0 - 90.0), s2, 1e-9);
        assertTrue(s2 > 0, "pivot less High + Low would be below zero, a support nobody could reach: " + s2);
    }

    @Test
    @DisplayName("TC falls below BC when the close was in the lower half of the day's range")
    void centralPivotsSwapPlaces() throws Exception {
        // Close 92 in a range of 90 to 120: Pivot (120+90+92)/3 is under BC 105, so TC = 2xPivot - BC is under both.
        session(test, D1, 120.0, 90.0, 92.0);
        candles(test, D2);

        Map<String, Double> levels = points("all", D2).getFirst().levels();

        assertTrue(levels.get("TC") < levels.get("Pivot") && levels.get("Pivot") < levels.get("BC"), levels.toString());
    }

    @Test
    @DisplayName("each selected session has its own levels, from the session before it, and the lines break between sessions")
    void eachSessionHasItsOwnLevels() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        session(test, D2, 130.0, 100.0, 112.0);
        candles(test, D2);
        candles(test, D3);

        List<Point> points = points("all", D2, D3);

        // D2's Pivot is D1's (120+90+108)/3 = 106; D3's is D2's (130+100+112)/3 = 114.
        assertEquals(java.util.Arrays.asList(106.0, 106.0, 106.0, null, 114.0, 114.0),
                points.stream().map(p -> p.levels().get("Pivot")).toList(),
                "the first candle of D3 is blank, so no ramp joins D2's level to D3's");
        assertEquals(java.util.Arrays.asList(166.0, 166.0, 166.0, null),
                points.stream().limit(4).map(p -> p.levels().get("R4")).toList());
    }

    @Test
    @DisplayName("a hole in this symbol's own history gives no levels, not ones from days earlier")
    void aMissingSessionIsNotPatchedWithAnOlderOne() throws Exception {
        session(test, D0, 120.0, 90.0, 108.0);
        session(other, D1, 50.0, 40.0, 45.0);
        candles(test, D2);

        assertTrue(points("all", D2).stream().allMatch(p -> p.levels().values().stream().allMatch(java.util.Objects::isNull)),
                "D1 was a trading day (another symbol has it); this symbol's D0 must not stand in");
    }

    @Test
    @DisplayName("the earliest stored session has none, nor does one after a session with no high or low")
    void noLevelsWithoutAHighAndLow() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        candles(test, D1);
        assertTrue(points("all", D1).stream().allMatch(p -> p.levels().values().stream().allMatch(java.util.Objects::isNull)));

        session(test, D2, null, null, 110.0);
        candles(test, D3);
        assertTrue(points("all", D3).stream().allMatch(p -> p.levels().values().stream().allMatch(java.util.Objects::isNull)),
                "the close alone is the previous close line; the levels need the whole day's range too");
    }

    @Test
    @DisplayName("the CPR levels box draws the levels out to R3/S3, R2/S2, R1/S1, the central pivots alone, or none")
    void theBoxNarrowsThem() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        candles(test, D2);

        assertEquals(LEVELS, drawn("all"));
        assertEquals(LEVELS, drawn("junk"), "anything the box does not offer, as in a hand-edited address, is every level");
        assertEquals(List.of("R3", "R2", "R1", "TC", "Pivot", "BC", "S1", "S2", "S3"), drawn("3"));
        assertEquals(List.of("R2", "R1", "TC", "Pivot", "BC", "S1", "S2"), drawn("2"));
        assertEquals(List.of("R1", "TC", "Pivot", "BC", "S1"), drawn("1"));
        assertEquals(List.of("TC", "Pivot", "BC"), drawn("0"));
        assertEquals(List.of(), drawn("off"));
    }

    @Test
    @DisplayName("drawn as the slide has it: thin green resistances, thin red supports, dashed blue central pivots")
    void drawnAsOnTheSlide() throws IOException {
        Map<String, Map<String, JsonNode>> byName = new LinkedHashMap<>();
        for (JsonNode override : panel().path("fieldConfig").path("overrides")) {
            Map<String, JsonNode> properties = new LinkedHashMap<>();
            override.path("properties").forEach(p -> properties.put(p.path("id").asText(), p.path("value")));
            byName.put(override.path("matcher").path("options").asText(), properties);
        }
        Map<String, String> colours = new LinkedHashMap<>();
        for (String name : List.of("R4", "R3", "R2", "R1")) {
            colours.put(name, "green");
        }
        for (String name : List.of("TC", "Pivot", "BC")) {
            colours.put(name, "light-blue");
        }
        for (String name : List.of("S1", "S2", "S3", "S4")) {
            colours.put(name, "red");
        }
        colours.forEach((name, colour) -> {
            Map<String, JsonNode> properties = byName.get(name);
            assertTrue(properties != null, name + " has a style");
            assertEquals("line", properties.get("custom.drawStyle").asText(), name);
            assertEquals(1, properties.get("custom.lineWidth").asInt(), name + ": thin, beside the candles");
            assertEquals(colour, properties.get("color").path("fixedColor").asText(), name);
            assertEquals(List.of("TC", "Pivot", "BC").contains(name), properties.containsKey("custom.lineStyle"),
                    name + ": only the central pivots are dashed");
            assertFalse(properties.containsKey("custom.lineInterpolation"),
                    name + ": stepping would draw a riser wherever a session's level changes");
        });
        assertTrue(panel().path("description").asText().contains("CPR levels"));
    }

    @Test
    @DisplayName("the CPR levels box offers every level, to none, and starts on every level")
    void theBox() throws IOException {
        JsonNode box = StreamSupport.stream(JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD))
                .path("templating").path("list").spliterator(), false)
                .filter(v -> v.path("name").asText().equals("cpr")).findFirst().orElseThrow();

        List<String> values = new ArrayList<>();
        box.path("options").forEach(o -> values.add(o.path("value").asText()));

        assertEquals(List.of("all", "3", "2", "1", "0", "off"), values);
        assertEquals("all", box.path("current").path("value").asText(), "the slide has every level");
        assertEquals("custom", box.path("type").asText());
        assertFalse(box.path("multi").asBoolean());
    }

    private static JsonNode panel() throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        return StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == 20).findFirst().orElseThrow();
    }

    private record Point(long time, Map<String, Double> levels) {
    }

    /** The levels the box lets through on the first candle, top to bottom, ignoring the blank ones. */
    private List<String> drawn(String box) throws Exception {
        Map<String, Double> levels = points(box, D2).getFirst().levels();
        return LEVELS.stream().filter(name -> levels.get(name) != null).toList();
    }

    /** The panel's own query, with Grafana's variables filled in, run over the seeded sessions, in time order. */
    private List<Point> points(String box, String... sessions) throws Exception {
        String list = String.join("','", sessions);
        String sql = panel().path("targets").get(0).path("queryText").asText()
                .replace("${session:sqlstring}", "'" + list + "'")
                .replace("${candles:sqlstring}", "'200'")
                .replace("${profile:sqlstring}", "'" + AccountProfileRepository.DEFAULT_PROFILE + "'")
                .replace("$symbol", "TEST")
                .replace("$cpr", box)
                .replace("$detector", "topk-nonoverlap/highlow/v1");
        assertTrue(!sql.contains("$"), "an unfilled variable would make this prove nothing: " + sql);
        return database.read(connection -> {
            List<Point> points = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    Map<String, Double> levels = new LinkedHashMap<>();
                    for (String name : LEVELS) {
                        double value = rs.getDouble(name);
                        levels.put(name, rs.wasNull() ? null : value);
                    }
                    points.add(new Point(rs.getLong("time"), levels));
                }
            }
            return points;
        });
    }

    private void session(long instrument, String date, Double high, Double low, double close) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO trading_day (instrument_id, session_date, interval_minutes, high, low, close, candle_count,
                                             source, ingested_at)
                    VALUES (?,?,1,?,?,?,375,'groww',0)""")) {
                insert.setLong(1, instrument);
                insert.setString(2, date);
                insert.setObject(3, high);
                insert.setObject(4, low);
                insert.setDouble(5, close);
                insert.executeUpdate();
            }
        });
    }

    /** Three one-minute candles in the session, stamped within its own UTC day so the test can tell sessions apart. */
    private void candles(long instrument, String date) {
        long base = java.time.LocalDate.parse(date).toEpochDay() * 86_400L + 4 * 3_600L;
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, open, high,
                                             low, close, volume, provisional, updated_at)
                    VALUES (?,1,?,?,100,101,99,100,1000,0,0)""")) {
                for (int minute = 0; minute < 3; minute++) {
                    insert.setLong(1, instrument);
                    insert.setLong(2, base + minute * 60L);
                    insert.setString(3, date);
                    insert.executeUpdate();
                }
            }
        });
    }
}
