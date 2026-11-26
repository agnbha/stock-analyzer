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
 * session each set of levels comes from, and the box whose six switches choose which lines are drawn. The prices are
 * invented.
 */
class CprLevelsTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");
    private static final String D0 = "2026-08-25";
    private static final String D1 = "2026-08-26";
    private static final String D2 = "2026-08-27";
    private static final String D3 = "2026-08-28";
    private static final List<String> LEVELS = List.of("R3", "R2", "R1", "TC", "Pivot", "BC", "S1", "S2", "S3");
    private static final List<String> NEXT = List.of("Next day TC", "Next day Pivot", "Next day BC");
    private static final List<String> LAST = List.of("Last day high", "Last day low");
    /** The box's picks that draw every support, resistance and central pivot, and nothing else. */
    private static final String EVERY = "r1,r2,r3,pivots";

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
        // R1 = 2x106-90 = 122, R2 = 106+30 = 136, R3 = 122+30 = 152.
        // S1 = 2x106-120 = 92, S2 = 106-30 = 76, S3 = 92-30 = 62.
        session(test, D1, 120.0, 90.0, 108.0);
        candles(test, D2);

        Map<String, Double> first = points(EVERY, D2).getFirst().levels();

        assertEquals(Map.ofEntries(Map.entry("R3", 152.0), Map.entry("R2", 136.0),
                Map.entry("R1", 122.0), Map.entry("TC", 107.0), Map.entry("Pivot", 106.0), Map.entry("BC", 105.0),
                Map.entry("S1", 92.0), Map.entry("S2", 76.0), Map.entry("S3", 62.0)), first);
    }

    @Test
    @DisplayName("S2 is the pivot less the day's range, as R2 is the pivot plus it: the slide's (High + Low) is a slip")
    void s2IsPivotLessTheRange() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        candles(test, D2);

        double s2 = points(EVERY, D2).getFirst().levels().get("S2");

        assertEquals(106.0 - (120.0 - 90.0), s2, 1e-9);
        assertTrue(s2 > 0, "pivot less High + Low would be below zero, a support nobody could reach: " + s2);
    }

    @Test
    @DisplayName("TC falls below BC when the close was in the lower half of the day's range")
    void centralPivotsSwapPlaces() throws Exception {
        // Close 92 in a range of 90 to 120: Pivot (120+90+92)/3 is under BC 105, so TC = 2xPivot - BC is under both.
        session(test, D1, 120.0, 90.0, 92.0);
        candles(test, D2);

        Map<String, Double> levels = points(EVERY, D2).getFirst().levels();

        assertTrue(levels.get("TC") < levels.get("Pivot") && levels.get("Pivot") < levels.get("BC"), levels.toString());
    }

    @Test
    @DisplayName("each selected session has its own levels, from the session before it, and the lines break between sessions")
    void eachSessionHasItsOwnLevels() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        session(test, D2, 130.0, 100.0, 112.0);
        candles(test, D2);
        candles(test, D3);

        List<Point> points = points(EVERY, D2, D3);

        // D2's Pivot is D1's (120+90+108)/3 = 106; D3's is D2's (130+100+112)/3 = 114.
        assertEquals(java.util.Arrays.asList(106.0, 106.0, 106.0, null, 114.0, 114.0),
                points.stream().map(p -> p.levels().get("Pivot")).toList(),
                "the first candle of D3 is blank, so no ramp joins D2's level to D3's");
        assertEquals(java.util.Arrays.asList(152.0, 152.0, 152.0, null),
                points.stream().limit(4).map(p -> p.levels().get("R3")).toList());
    }

    @Test
    @DisplayName("a hole in this symbol's own history gives no levels, not ones from days earlier")
    void aMissingSessionIsNotPatchedWithAnOlderOne() throws Exception {
        session(test, D0, 120.0, 90.0, 108.0);
        session(other, D1, 50.0, 40.0, 45.0);
        candles(test, D2);

        assertTrue(points(EVERY + ",hl", D2).stream().allMatch(p -> p.levels().values().stream().allMatch(java.util.Objects::isNull)
                        && p.last().values().stream().allMatch(java.util.Objects::isNull)),
                "D1 was a trading day (another symbol has it); this symbol's D0 must not stand in");
    }

    @Test
    @DisplayName("the earliest stored session has none, nor does one after a session with no high or low")
    void noLevelsWithoutAHighAndLow() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        candles(test, D1);
        assertTrue(points(EVERY, D1).stream().allMatch(p -> p.levels().values().stream().allMatch(java.util.Objects::isNull)));

        session(test, D2, null, null, 110.0);
        candles(test, D3);
        assertTrue(points(EVERY, D3).stream().allMatch(p -> p.levels().values().stream().allMatch(java.util.Objects::isNull)),
                "the close alone is the previous close line; the levels need the whole day's range too");
    }

    @Test
    @DisplayName("each choice in the CPR levels box is a switch of its own: R1 and S1, R2 and S2, R3 and S3, the central pivots")
    void eachChoiceIsItsOwnSwitch() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        candles(test, D2);

        assertEquals(LEVELS, drawn(EVERY));
        assertEquals(List.of("R1", "S1"), drawn("r1"));
        assertEquals(List.of("R2", "S2"), drawn("r2"));
        assertEquals(List.of("R3", "S3"), drawn("r3"));
        assertEquals(List.of("TC", "Pivot", "BC"), drawn("pivots"));
        assertEquals(List.of("R3", "R1", "TC", "Pivot", "BC", "S1", "S3"), drawn("r3,r1,pivots"), "in any mix, in any order");
        assertEquals(List.of("R2", "S2"), drawn("r2,predicted,hl"), "and the other two choices draw none of these");
        assertEquals(List.of(), drawn(""), "an empty list draws none of them (the box itself never sends one: Grafana puts the first choice back)");
        assertEquals(List.of("TC", "Pivot", "BC"), drawn("junk,pivots,r"),
                "anything the box does not offer, as in a hand-edited address, is ignored; a name counts only whole");
        assertEquals(List.of(), drawn("junk,pivot,r11"));
    }

    @Test
    @DisplayName("tomorrow's central pivots come from the newest session's own prices: its highest high, lowest low, latest close")
    void tomorrowsPivotsFromTodaysPrices() throws Exception {
        // High 120, Low 90, Close 108 (the last minute's): Pivot 106, BC 105, TC = (106-105)+106 = 107.
        aSessionOf120And90Closing108(test, D2);

        Map<String, Double> next = points("pivots,predicted", D2).getFirst().next();

        assertEquals(Map.of("Next day TC", 107.0, "Next day Pivot", 106.0, "Next day BC", 105.0), next,
                "there is no session before D2, so no CPR of its own, and tomorrow's is there all the same");
    }

    @Test
    @DisplayName("on the fly: each new minute moves them, as the day's high, low and last price do")
    void tomorrowsPivotsMoveWithTheDay() throws Exception {
        aSessionOf120And90Closing108(test, D2);
        assertEquals(106.0, points("pivots,predicted", D2).getFirst().next().get("Next day Pivot"));

        // A new high of 126 and a last price of 114, the low still 90: Pivot (126+90+114)/3 = 110, BC 108, TC 112.
        candle(test, D2, 3, 126, 96, 114);

        assertEquals(Map.of("Next day TC", 112.0, "Next day Pivot", 110.0, "Next day BC", 108.0),
                points("pivots,predicted", D2).getFirst().next());
    }

    @Test
    @DisplayName("only the newest selected session has them, flat across it and blank on its first candle like every line")
    void onlyTheNewestSessionHasThem() throws Exception {
        aSessionOf120And90Closing108(test, D2);
        aSessionOf120And90Closing108(test, D3);

        List<Point> points = points("pivots,predicted", D2, D3);

        assertEquals(java.util.Arrays.asList(null, null, null, null, 106.0, 106.0),
                points.stream().map(p -> p.next().get("Next day Pivot")).toList(),
                "D2's are the lines D3 already draws; D3's first candle is blank, as every line's is");
    }

    @Test
    @DisplayName("they are the lines the next session draws as its own: tomorrow's from today's prices are tomorrow's CPR")
    void theNextSessionDrawsThem() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        aSessionOf120And90Closing108(test, D1);
        candles(test, D2);

        Map<String, Double> tomorrows = points("pivots,predicted", D1).getFirst().next();
        Point next = points("pivots", D2).getFirst();

        assertEquals(tomorrows.get("Next day TC"), next.levels().get("TC"));
        assertEquals(tomorrows.get("Next day Pivot"), next.levels().get("Pivot"));
        assertEquals(tomorrows.get("Next day BC"), next.levels().get("BC"));
    }

    @Test
    @DisplayName("they stay within the session's own range, so they never stretch the chart")
    void withinTheSessionsRange() throws Exception {
        // Closes at the very high, and at the very low: the extremes of where TC and BC can fall.
        for (double close : new double[]{120.0, 90.0, 105.0}) {
            database.inTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM live_candle");
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            candle(test, D2, 0, 120, 100, 110);
            candle(test, D2, 1, Math.max(115, close), 90, close);

            for (double level : points("pivots,predicted", D2).getFirst().next().values()) {
                assertTrue(level >= 90.0 && level <= 120.0, "close " + close + ": " + level);
            }
        }
    }

    @Test
    @DisplayName("tomorrow's pivots are drawn only when Predicted CPR is picked, whatever else is")
    void predictedCprIsOffUnlessPicked() throws Exception {
        aSessionOf120And90Closing108(test, D2);

        for (String picks : List.of("pivots", EVERY + ",hl", "", "junk", "predictedx", "pre,dicted", "predict")) {
            assertTrue(points(picks, D2).getFirst().next().values().stream().allMatch(java.util.Objects::isNull),
                    "picks '" + picks + "': only Predicted CPR draws them");
        }
    }

    @Test
    @DisplayName("Predicted CPR is a switch of its own: it draws them with the other choices on, or off")
    void predictedCprIsItsOwnSwitch() throws Exception {
        aSessionOf120And90Closing108(test, D2);

        for (String picks : List.of("predicted", "pivots,predicted", "r1,r2,r3,pivots,predicted,hl", "hl,predicted")) {
            assertTrue(points(picks, D2).getFirst().next().values().stream().allMatch(java.util.Objects::nonNull),
                    "picks '" + picks + "': tomorrow's pivots are drawn once Predicted CPR is picked");
        }
        Point alone = points("predicted", D2).getFirst();
        assertTrue(alone.levels().values().stream().allMatch(java.util.Objects::isNull),
                "and it brings none of the levels with it");
        assertTrue(alone.last().values().stream().allMatch(java.util.Objects::isNull));
    }

    @Test
    @DisplayName("Last Day's High and Low are the highest and lowest prices of the session before, flat across the session")
    void lastDaysHighAndLow() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        candlesRanging(test, D2, 125.0, 80.0);

        List<Point> points = points("hl", D2);

        assertEquals(3, points.size());
        for (Point point : points) {
            assertEquals(Map.of("Last day high", 120.0, "Last day low", 90.0), point.last());
        }
    }

    @Test
    @DisplayName("each session has its own, from the session before it, and the lines break between sessions")
    void lastDaysHighAndLowForEachSession() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        session(test, D2, 130.0, 100.0, 112.0);
        candlesRanging(test, D2, 125.0, 80.0);
        candlesRanging(test, D3, 135.0, 95.0);

        List<Point> points = points("hl", D2, D3);

        assertEquals(java.util.Arrays.asList(120.0, 120.0, 120.0, null, 130.0, 130.0),
                points.stream().map(p -> p.last().get("Last day high")).toList());
        assertEquals(java.util.Arrays.asList(90.0, 90.0, 90.0, null, 100.0, 100.0),
                points.stream().map(p -> p.last().get("Last day low")).toList());
    }

    @Test
    @DisplayName("like the supports and resistances, each is drawn only on a session whose price reached it")
    void lastDaysHighAndLowOnlyWhereReached() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        session(test, D2, 120.0, 90.0, 108.0);
        session(test, D3, 120.0, 90.0, 108.0);
        candlesRanging(test, D2, 125.0, 95.0);                  // reaches 120, not 90
        candlesRanging(test, D3, 110.0, 100.0);                 // reaches neither
        candlesRanging(test, "2026-08-31", 130.0, 85.0);        // reaches both (the session before is D3: 120 and 90)

        assertEquals(Map.of("Last day high", 120.0), nonBlank(points("hl", D2).getFirst().last()));
        assertEquals(Map.of(), nonBlank(points("hl", D3).getFirst().last()));
        assertEquals(Map.of("Last day high", 120.0, "Last day low", 90.0),
                nonBlank(points("hl", "2026-08-31").getFirst().last()));
    }

    @Test
    @DisplayName("it is a switch of its own: it draws no other line, and no other choice draws it")
    void lastDaysHighAndLowIsItsOwnSwitch() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        candlesRanging(test, D2, 125.0, 80.0);

        Point alone = points("hl", D2).getFirst();
        assertTrue(alone.levels().values().stream().allMatch(java.util.Objects::isNull), alone.levels().toString());
        assertTrue(alone.next().values().stream().allMatch(java.util.Objects::isNull));
        assertTrue(points(EVERY + ",predicted", D2).getFirst().last().values().stream().allMatch(java.util.Objects::isNull),
                "every other choice picked, this one not");
    }

    @Test
    @DisplayName("none for the earliest stored session, nor after a session with no high or low")
    void noLastDaysHighAndLowWithoutOne() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        candles(test, D1);
        assertTrue(points("hl", D1).stream().allMatch(p -> p.last().values().stream().allMatch(java.util.Objects::isNull)));

        session(test, D2, null, null, 110.0);
        candles(test, D3);
        assertTrue(points("hl", D3).stream().allMatch(p -> p.last().values().stream().allMatch(java.util.Objects::isNull)));
    }

    @Test
    @DisplayName("the previous close is not in the box: it is drawn whatever is picked, or nothing")
    void thePreviousCloseIsAlwaysThere() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);
        candles(test, D2);

        for (String picks : List.of("", "pivots", "hl", "r1,r2,r3,pivots,predicted,hl")) {
            assertEquals(108.0, points(picks, D2).getFirst().previousClose(), "picks '" + picks + "'");
        }
    }

    @Test
    @DisplayName("a support or resistance the price did not reach is not drawn, so it cannot stretch the chart")
    void aLevelThePriceDidNotReachIsNotDrawn() throws Exception {
        // From 120 / 90 / 108: R1 122, R2 136, R3 152 and S1 92, S2 76, S3 62; the pivots 105 to 107.
        session(test, D1, 120.0, 90.0, 108.0);
        candlesRanging(test, D2, 125.0, 80.0);

        assertEquals(List.of("R1", "TC", "Pivot", "BC", "S1"), drawnOnTheFirstCandle(EVERY, D2),
                "R1 (122) and S1 (92) are inside 80 to 125; R2 (136), S2 (76) and the rest are outside it");
        for (Point point : points(EVERY, D2)) {
            for (Map.Entry<String, Double> level : point.levels().entrySet()) {
                assertTrue(level.getValue() == null || (level.getValue() >= 80.0 && level.getValue() <= 125.0),
                        level.getKey() + " would be outside the candles: " + level.getValue());
            }
        }
    }

    @Test
    @DisplayName("a level exactly at the day's high or low is drawn, one a hair beyond it is not")
    void theEdgesOfTheRange() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);                  // R1 = 122, S1 = 92
        candlesRanging(test, D2, 122.0, 92.0);
        assertEquals(List.of("R1", "TC", "Pivot", "BC", "S1"), drawnOnTheFirstCandle("r1,pivots", D2), "on the edge");

        candlesRanging(test, D3, 121.99, 92.01);
        session(test, D2, 120.0, 90.0, 108.0);                  // D3's levels are the same: R1 = 122, S1 = 92
        assertEquals(List.of("TC", "Pivot", "BC"), drawnOnTheFirstCandle("r1,pivots", D3), "a hair inside it");
    }

    @Test
    @DisplayName("they appear as the day goes: a new high that reaches R2 draws it from the next read")
    void theyAppearAsThePriceGetsToThem() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);                  // R1 = 122, R2 = 136, R3 = 152
        candle(test, D2, 0, 125, 90, 110);
        assertEquals(List.of("R1", "TC", "Pivot", "BC", "S1"), drawnOnTheFirstCandle(EVERY, D2));

        candle(test, D2, 1, 140, 105, 138);                     // the price gets past R2

        assertEquals(List.of("R2", "R1", "TC", "Pivot", "BC", "S1"), drawnOnTheFirstCandle(EVERY, D2));
    }

    @Test
    @DisplayName("the previous close and the central pivots are drawn whatever the range: they are wanted in view")
    void thePreviousCloseAndCentralPivotsAreAlwaysDrawn() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);                  // pivots 105 to 107, previous close 108
        candlesRanging(test, D2, 150.0, 140.0);                 // a gap up: all of them are below the day's range

        Point first = points(EVERY, D2).getFirst();

        assertEquals(List.of(), LEVELS.stream().filter(n -> first.levels().get(n) != null && !List.of("TC", "Pivot", "BC").contains(n)).toList(),
                "no support or resistance is in 140 to 150 (R2 is 136, R3 152)");
        assertEquals(Map.of("TC", 107.0, "Pivot", 106.0, "BC", 105.0),
                Map.of("TC", first.levels().get("TC"), "Pivot", first.levels().get("Pivot"), "BC", first.levels().get("BC")));
        assertEquals(108.0, first.previousClose());
    }

    @Test
    @DisplayName("each session is judged by its own range, not the range of the sessions drawn with it")
    void eachSessionByItsOwnRange() throws Exception {
        session(test, D1, 120.0, 90.0, 108.0);                  // D2's R1 = 122
        session(test, D2, 130.0, 100.0, 112.0);                 // D3's Pivot 114, R1 = 128
        candlesRanging(test, D2, 121.0, 80.0);                  // D2 does not reach its R1 (122)...
        candlesRanging(test, D3, 130.0, 100.0);                 // ...though D3's range holds 122; D3 does reach its own 128

        assertEquals(java.util.Arrays.asList(null, null, null, null, 128.0, 128.0),
                points("r1", D2, D3).stream().map(p -> p.levels().get("R1")).toList());
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
        for (String name : List.of("R3", "R2", "R1")) {
            colours.put(name, "green");
        }
        for (String name : List.of("TC", "Pivot", "BC")) {
            colours.put(name, "light-blue");
        }
        for (String name : List.of("S1", "S2", "S3")) {
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
            assertEquals(!List.of("TC", "Pivot", "BC").contains(name),
                    properties.containsKey("custom.hideFrom") && properties.get("custom.hideFrom").path("legend").asBoolean(),
                    name + ": the legend names the central pivots; the supports and resistances are there to add, not on show");
        });
        for (String name : NEXT) {
            Map<String, JsonNode> properties = byName.get(name);
            assertTrue(properties != null, name + " has a style");
            assertEquals("line", properties.get("custom.drawStyle").asText(), name);
            assertEquals(1, properties.get("custom.lineWidth").asInt(), name);
            assertEquals("orange", properties.get("color").path("fixedColor").asText(), name + ": apart from today's");
            assertTrue(properties.containsKey("custom.lineStyle"), name + ": dashed, as the central pivots are");
            assertFalse(properties.containsKey("custom.hideFrom"), name + ": named in the legend");
        }
        for (String name : LAST) {
            Map<String, JsonNode> properties = byName.get(name);
            assertTrue(properties != null, name + " has a style");
            assertEquals("line", properties.get("custom.drawStyle").asText(), name);
            assertEquals(1, properties.get("custom.lineWidth").asInt(), name);
            assertEquals("dark-yellow", properties.get("color").path("fixedColor").asText(), name + ": apart from the rest");
            assertTrue(properties.containsKey("custom.lineStyle"), name + ": dashed");
            assertFalse(properties.containsKey("custom.hideFrom"), name + ": named in the legend");
        }
        assertFalse(byName.containsKey("R4") || byName.containsKey("S4"), "the box has no switch for them");
        assertTrue(panel().path("description").asText().contains("CPR levels"));
        assertTrue(panel().path("description").asText().contains("Last Day's High and Low"));
    }

    @Test
    @DisplayName("the CPR levels box is a multi-select of the six choices, in order, and starts on Central Pivots alone")
    void theBox() throws IOException {
        JsonNode box = variable("cpr_lines");

        List<String> values = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<String> selected = new ArrayList<>();
        box.path("options").forEach(o -> {
            values.add(o.path("value").asText());
            texts.add(o.path("text").asText());
            if (o.path("selected").asBoolean()) {
                selected.add(o.path("value").asText());
            }
        });

        assertEquals(List.of("r1", "r2", "r3", "pivots", "predicted", "hl"), values);
        assertEquals(List.of("R1 and S1", "R2 and S2", "R3 and S3", "Central Pivots", "Predicted CPR", "Last Day's High and Low"), texts);
        assertEquals("CPR levels", box.path("label").asText());
        assertEquals("custom", box.path("type").asText());
        assertTrue(box.path("multi").asBoolean(), "a multi-select dropdown");
        assertFalse(box.path("includeAll").asBoolean(), "no All: it would tick the predicted and last-day lines too");
        assertEquals(List.of("pivots"), selected, "only the central pivots are ticked to begin with");
        List<String> current = new ArrayList<>();
        box.path("current").path("value").forEach(v -> current.add(v.asText()));
        assertEquals(List.of("pivots"), current);
        assertEquals(List.of("Central Pivots"), StreamSupport.stream(box.path("current").path("text").spliterator(), false)
                .map(JsonNode::asText).toList());
        assertEquals("R1 and S1 : r1, R2 and S2 : r2, R3 and S3 : r3, Central Pivots : pivots, Predicted CPR : predicted, "
                + "Last Day's High and Low : hl", box.path("query").asText(), "the options Grafana rebuilds from the query agree");
        assertTrue(panel().path("targets").get(0).path("queryText").asText().contains("${cpr_lines:csv}"),
                "the query reads the box as a comma list");
    }

    @Test
    @DisplayName("the single-select boxes it replaces are gone, so a stale address cannot override its default")
    void theOldBoxesAreGone() throws IOException {
        List<String> names = StreamSupport.stream(JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD))
                .path("templating").path("list").spliterator(), false).map(v -> v.path("name").asText()).toList();

        assertEquals(1, names.stream().filter("cpr_lines"::equals).count());
        for (String old : List.of("cpr", "cpr_levels", "cpr_next")) {
            assertFalse(names.contains(old), old + ": an address carrying var-" + old + "=... must be ignored, not read as a pick");
        }
        String sql = panel().path("targets").get(0).path("queryText").asText();
        assertFalse(sql.contains("$cpr_levels") || sql.contains("$cpr_next"));
    }

    private static JsonNode panel() throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        return StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == 20).findFirst().orElseThrow();
    }

    private record Point(long time, Map<String, Double> levels, Map<String, Double> next, Map<String, Double> last,
                         Double previousClose) {
    }

    private static JsonNode variable(String name) throws IOException {
        return StreamSupport.stream(JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD))
                .path("templating").path("list").spliterator(), false)
                .filter(v -> v.path("name").asText().equals(name)).findFirst().orElseThrow();
    }

    /** The values that are not blank. */
    private static Map<String, Double> nonBlank(Map<String, Double> values) {
        Map<String, Double> drawn = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (value != null) {
                drawn.put(name, value);
            }
        });
        return drawn;
    }

    /** The levels the picks let through on the first candle, top to bottom, ignoring the blank ones. */
    private List<String> drawn(String picks) throws Exception {
        Map<String, Double> levels = points(picks, D2).getFirst().levels();
        return LEVELS.stream().filter(name -> levels.get(name) != null).toList();
    }

    /**
     * The panel's own query, with Grafana's variables filled in, run over the seeded sessions, in time order. The picks are
     * the box's choices as Grafana hands them over, a comma list: {@code "r1,pivots"} is those two ticked.
     */
    private List<Point> points(String picks, String... sessions) throws Exception {
        String list = String.join("','", sessions);
        String sql = panel().path("targets").get(0).path("queryText").asText()
                .replace("${session:sqlstring}", "'" + list + "'")
                .replace("${candles:sqlstring}", "'200'")
                .replace("${profile:sqlstring}", "'" + AccountProfileRepository.DEFAULT_PROFILE + "'")
                .replace("$symbol", "TEST")
                .replace("${cpr_lines:csv}", picks)
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
                    Map<String, Double> next = new LinkedHashMap<>();
                    for (String name : NEXT) {
                        double value = rs.getDouble(name);
                        next.put(name, rs.wasNull() ? null : value);
                    }
                    Map<String, Double> last = new LinkedHashMap<>();
                    for (String name : LAST) {
                        double value = rs.getDouble(name);
                        last.put(name, rs.wasNull() ? null : value);
                    }
                    double previous = rs.getDouble("Previous close");
                    points.add(new Point(rs.getLong("time"), levels, next, last, rs.wasNull() ? null : previous));
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

    /** One candle of the session at its minute: open at the close, the high, low and close given. */
    private void candle(long instrument, String date, int minute, double high, double low, double close) {
        long at = java.time.LocalDate.parse(date).toEpochDay() * 86_400L + 4 * 3_600L + minute * 60L;
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, open, high,
                                             low, close, volume, provisional, updated_at)
                    VALUES (?,1,?,?,?,?,?,?,1000,0,0)""")) {
                insert.setLong(1, instrument);
                insert.setLong(2, at);
                insert.setString(3, date);
                insert.setDouble(4, close);
                insert.setDouble(5, high);
                insert.setDouble(6, low);
                insert.setDouble(7, close);
                insert.executeUpdate();
            }
        });
    }

    /** Three candles in the session, each from {@code low} to {@code high}, closing midway. */
    private void candlesRanging(long instrument, String date, double high, double low) {
        for (int minute = 0; minute < 3; minute++) {
            candle(instrument, date, minute, high, low, (high + low) / 2);
        }
    }

    /** The levels of the first candle that are drawn, top to bottom. */
    private List<String> drawnOnTheFirstCandle(String picks, String... sessions) throws Exception {
        Map<String, Double> levels = points(picks, sessions).getFirst().levels();
        return LEVELS.stream().filter(name -> levels.get(name) != null).toList();
    }

    /** A session whose prices run to a high of 120 and a low of 90, closing at 108: the numbers of the slide's example. */
    private void aSessionOf120And90Closing108(long instrument, String date) {
        candle(instrument, date, 0, 120, 100, 110);
        candle(instrument, date, 1, 115, 95, 100);
        candle(instrument, date, 2, 112, 90, 108);
    }

    /**
     * Three one-minute candles in the session, stamped within its own UTC day so the test can tell sessions apart, and
     * over a range of 20 to 200: wide enough to take every level, which are drawn only where the price reached them.
     */
    private void candles(long instrument, String date) {
        long base = java.time.LocalDate.parse(date).toEpochDay() * 86_400L + 4 * 3_600L;
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, open, high,
                                             low, close, volume, provisional, updated_at)
                    VALUES (?,1,?,?,100,200,20,100,1000,0,0)""")) {
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
