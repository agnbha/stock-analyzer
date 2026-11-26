package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.canslim.BaseMark;
import com.stockanalyzer.store.jdbc.SqliteCanSlimRepository;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base boxes on CAN SLIM's chart, checked by running the panel's own query over seeded daily bars and bases: which bars
 * carry a box's top and bottom, in daily and in weekly candles, and how the lines are styled to read as a filled rectangle.
 */
class CanSlimChartBasesTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "06-canslim.json");
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);

    @TempDir
    Path directory;

    private Database database;
    private List<LocalDate> days;
    private long alpha;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        SqliteInstrumentRepository instruments = new SqliteInstrumentRepository(database);
        alpha = instruments.findOrCreate("ALPHA", "NSE", "CASH");
        long beta = instruments.findOrCreate("BETA", "NSE", "CASH");
        days = new ArrayList<>();
        for (LocalDate day = MONDAY; days.size() < 30; day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
        }
        store(alpha);
        store(beta);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private BaseMark box(BaseMark.Kind kind, int from, int to, double pivot, double low) {
        return new BaseMark(kind, days.get(from), days.get(to), pivot, low, to - from + 1);
    }

    @Test
    @DisplayName("a bar inside a base carries its pivot and its lowest low, and a bar outside any base carries none")
    void barsInsideABox() throws Exception {
        new SqliteCanSlimRepository(database).saveBases(Map.of(alpha, List.of(
                box(BaseMark.Kind.BREAKOUT, 3, 12, 150.0, 138.0))));

        List<Map<String, Double>> rows = rows("ALPHA", "daily");

        assertEquals(30, rows.size());
        for (int i = 0; i < 30; i++) {
            boolean inside = i >= 3 && i <= 12;
            assertEquals(inside ? 150.0 : null, rows.get(i).get("Base top"), "bar " + i);
            assertEquals(inside ? 138.0 : null, rows.get(i).get("Base bottom"), "bar " + i);
            assertNull(rows.get(i).get("Base 2 top"), "one base, one pair of lines");
        }
    }

    @Test
    @DisplayName("bases are drawn from alternating pairs of lines, so two that meet are two boxes and not one ramp")
    void neighboursAlternate() throws Exception {
        new SqliteCanSlimRepository(database).saveBases(Map.of(alpha, List.of(
                box(BaseMark.Kind.BREAKOUT, 2, 10, 150.0, 138.0),
                box(BaseMark.Kind.BREAKOUT, 10, 18, 200.0, 180.0),
                box(BaseMark.Kind.CURRENT, 24, 29, 260.0, 240.0))));

        List<Map<String, Double>> rows = rows("ALPHA", "daily");

        assertEquals(150.0, rows.get(10).get("Base top"), "the first ends where the second starts: both are on that bar");
        assertEquals(200.0, rows.get(10).get("Base 2 top"));
        assertEquals(180.0, rows.get(10).get("Base 2 bottom"));
        assertEquals(200.0, rows.get(15).get("Base 2 top"));
        assertNull(rows.get(15).get("Base top"));
        assertNull(rows.get(20).get("Base top"));
        assertNull(rows.get(20).get("Base 2 top"), "between bases, nothing");
        assertEquals(260.0, rows.get(29).get("Base top"), "the third goes back to the first pair, after a gap");
        assertEquals(240.0, rows.get(26).get("Base bottom"));
    }

    @Test
    @DisplayName("in weekly candles a base is on every week it touches")
    void weekly() throws Exception {
        // Days 3-12 are the Thursday and Friday of week 1, week 2 and the Monday and Tuesday of week 3.
        new SqliteCanSlimRepository(database).saveBases(Map.of(alpha, List.of(
                box(BaseMark.Kind.BREAKOUT, 3, 12, 150.0, 138.0),
                box(BaseMark.Kind.CURRENT, 27, 29, 260.0, 240.0))));

        List<Map<String, Double>> rows = rows("ALPHA", "weekly");

        assertEquals(6, rows.size());
        for (int week = 0; week < 6; week++) {
            boolean first = week <= 2;
            assertEquals(first ? 150.0 : null, rows.get(week).get("Base top"), "week " + week + " top");
            assertEquals(first ? 138.0 : null, rows.get(week).get("Base bottom"), "week " + week + " bottom");
        }
        assertEquals(260.0, rows.get(5).get("Base 2 top"), "a base inside the last week is on that week, in the second pair");
        assertEquals(240.0, rows.get(5).get("Base 2 bottom"));
        assertNull(rows.get(4).get("Base 2 top"));
    }

    @Test
    @DisplayName("another stock's bases are not drawn, nor any when a stock has none")
    void onlyThisStocks() throws Exception {
        new SqliteCanSlimRepository(database).saveBases(Map.of(alpha, List.of(
                box(BaseMark.Kind.BREAKOUT, 3, 12, 150.0, 138.0))));

        for (Map<String, Double> row : rows("BETA", "daily")) {
            assertNull(row.get("Base top"));
            assertNull(row.get("Base bottom"));
            assertNull(row.get("Base 2 top"));
            assertNull(row.get("Base 2 bottom"));
        }
        assertEquals(30, rows("BETA", "daily").size(), "the candles are all there");
    }

    @Test
    @DisplayName("each box is two lines with the space between filled, so it reads as a rectangle on the candles")
    void styledAsABox() throws IOException {
        Map<String, Map<String, JsonNode>> byName = new LinkedHashMap<>();
        for (JsonNode override : panel().path("fieldConfig").path("overrides")) {
            Map<String, JsonNode> properties = new LinkedHashMap<>();
            override.path("properties").forEach(p -> properties.put(p.path("id").asText(), p.path("value")));
            byName.put(override.path("matcher").path("options").asText(), properties);
        }

        for (String[] pair : new String[][]{{"Base top", "Base bottom"}, {"Base 2 top", "Base 2 bottom"}}) {
            Map<String, JsonNode> top = byName.get(pair[0]);
            Map<String, JsonNode> bottom = byName.get(pair[1]);
            assertTrue(top != null && bottom != null, pair[0] + " and " + pair[1] + " have styles");
            assertEquals(pair[1], top.get("custom.fillBelowTo").asText(), "the top fills down to its own bottom");
            assertTrue(top.get("custom.fillOpacity").asInt() > 0 && top.get("custom.fillOpacity").asInt() < 50,
                    "filled, but see-through: the candles show through");
            assertFalse(bottom.containsKey("custom.fillBelowTo"));
            for (Map<String, JsonNode> line : List.of(top, bottom)) {
                assertEquals("line", line.get("custom.drawStyle").asText());
                assertEquals(1, line.get("custom.lineWidth").asInt(), "a thin edge");
                assertEquals("never", line.get("custom.showPoints").asText());
                assertEquals("yellow", line.get("color").path("fixedColor").asText());
            }
        }
        assertFalse(byName.get("Base top").containsKey("custom.hideFrom"), "one entry in the legend names the boxes");
        for (String name : List.of("Base bottom", "Base 2 top", "Base 2 bottom")) {
            assertTrue(byName.get(name).get("custom.hideFrom").path("legend").asBoolean(), name);
            assertFalse(byName.get(name).get("custom.hideFrom").path("tooltip").asBoolean(), name + ": still read on hover");
        }
        assertTrue(panel().path("title").asText().contains("the bases"));
        assertTrue(panel().path("description").asText().contains("**Base**"));
    }

    private static JsonNode panel() throws IOException {
        for (JsonNode panel : JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD)).path("panels")) {
            if (panel.path("id").asInt() == 20) {
                return panel;
            }
        }
        throw new AssertionError("no panel 20");
    }

    private List<Map<String, Double>> rows(String symbol, String frame) throws IOException {
        String query = panel().path("targets").get(0).path("queryText").asText()
                .replace("$symbol", symbol).replace("$frame", frame);
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

    private void store(long instrument) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO daily_bar (instrument_id, session_date, ts_epoch, open, high, low, close, volume)
                    VALUES (?,?,?,?,?,?,?,?)""")) {
                for (LocalDate day : days) {
                    insert.setLong(1, instrument);
                    insert.setString(2, day.toString());
                    insert.setLong(3, day.atTime(9, 15).atZone(java.time.ZoneId.of("Asia/Kolkata")).toEpochSecond());
                    insert.setDouble(4, 100);
                    insert.setDouble(5, 105);
                    insert.setDouble(6, 95);
                    insert.setDouble(7, 102);
                    insert.setLong(8, 1000);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }
}
