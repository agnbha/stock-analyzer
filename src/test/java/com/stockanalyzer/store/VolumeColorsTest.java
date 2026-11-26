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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The volume bars under the daily candles of Stock Trend and CAN SLIM, colored by how each day's volume compares with
 * the stock's own, by running the two charts' queries. An ordinary day is red when the stock closed below the previous
 * day's close, else green (the candles compare the close with the day's open, and keep their colors); light blue at or
 * above the 90th percentile of the last 20 sessions, yellow at or below the 10th; white on a bull snort (on 3 times the
 * average volume of the 50 sessions before), over any other color. The highest or lowest volume of the last 3 months
 * gets a Q dot above its bar, of the last 12 months a Y dot; a bull snort that is also one of them keeps its white bar and
 * shows the extreme too, as a narrow core in the extreme's color and a line of its own in the tooltip; over 1 million
 * shares on a close 4.95% or more above the
 * previous one (a 5% upper circuit, rounded to the tick), a glow behind it. The volumes and prices are invented.
 */
class VolumeColorsTest {

    private static final Path TREND = Path.of("grafana", "dashboards", "04-stock-trend.json");
    private static final Path CANSLIM = Path.of("grafana", "dashboards", "06-canslim.json");
    private static final Map<Path, Integer> CHARTS = Map.of(TREND, 10, CANSLIM, 20);
    private static final LocalDate FIRST = LocalDate.of(2026, 1, 5);

    private static final String Q = "Q · highest or lowest in 3 months";
    private static final String Y = "Y · highest or lowest in 12 months";
    private static final String GLOW = "Over 1 million shares, up 4.95% or more";

    private enum Kind { DOWN, UP, HEAVY, LIGHT, BULL_SNORT, QUARTER_HIGH, QUARTER_LOW, YEAR_HIGH, YEAR_LOW }

    /** Each kind of day's bar series, and its color. */
    private static final Map<Kind, String> BAR = new EnumMap<>(Map.of(
            Kind.DOWN, "Volume, price down",
            Kind.UP, "Volume, price up",
            Kind.HEAVY, "Volume ≥ 90th percentile of 20 sessions",
            Kind.LIGHT, "Volume ≤ 10th percentile of 20 sessions",
            Kind.BULL_SNORT, "Bull snort (range ≥ 5%, top-third close, up, 3× volume)",
            Kind.QUARTER_HIGH, "Highest volume in 3 months",
            Kind.QUARTER_LOW, "Lowest volume in 3 months",
            Kind.YEAR_HIGH, "Highest volume in 12 months",
            Kind.YEAR_LOW, "Lowest volume in 12 months"));
    private static final Map<Kind, String> COLOR = new EnumMap<>(Map.of(
            Kind.DOWN, "red", Kind.UP, "green", Kind.HEAVY, "light-blue", Kind.LIGHT, "yellow",
            Kind.BULL_SNORT, "white", Kind.QUARTER_HIGH, "light-blue", Kind.QUARTER_LOW, "yellow",
            Kind.YEAR_HIGH, "light-blue", Kind.YEAR_LOW, "yellow"));

    /**
     * The series that name a bull snort's 3- or 12-month extreme as well: the white bar is the day's one bar, so the
     * extreme is drawn as a narrow core on it, in the extreme's color, and is a line in the tooltip.
     */
    private static final Map<Kind, String> ALSO = new EnumMap<>(Map.of(
            Kind.QUARTER_HIGH, "Also the highest volume in 3 months",
            Kind.QUARTER_LOW, "Also the lowest volume in 3 months",
            Kind.YEAR_HIGH, "Also the highest volume in 12 months",
            Kind.YEAR_LOW, "Also the lowest volume in 12 months"));

    private record Session(LocalDate date, double open, double high, double low, double close, long volume) {
    }

    /** A day's bar, its 3- or 12-month extreme if it is one (Q or Y), and whether it glows. */
    private record Drawn(Kind bar, Kind extreme, boolean glow) {
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
    @DisplayName("every day's bar is the one the rules give, the same on both charts, over 15 months of volumes")
    void everyDayAsTheRulesSay() {
        Random random = new Random(11);
        // Every seventh day a spike, so that some days reach 3 times the 50-session average: a bull snort's volume.
        List<Session> rising = sessions(320,
                i -> Math.round((100_000 + 6_000 * i) * (0.5 + random.nextDouble()) * (i % 7 == 3 ? 6 : 1)), random);
        List<Session> ties = sessions(320,
                i -> 500 * Math.round((9000 - 20 * i) * (0.5 + random.nextDouble()) / 500), random);
        store("RISING", rising);
        store("TIES", ties);
        Set<Kind> seen = EnumSet.noneOf(Kind.class);
        int glows = 0;
        int snortsOnExtremes = 0;

        for (Map.Entry<String, List<Session>> stock : Map.of("RISING", rising, "TIES", ties).entrySet()) {
            List<Session> days = stock.getValue();
            for (Path chart : CHARTS.keySet()) {
                List<Map<String, Double>> rows = rows(chart, stock.getKey());
                assertEquals(days.size(), rows.size(), chart + " " + stock.getKey());
                for (int i = 0; i < days.size(); i++) {
                    Drawn drawn = expected(days, i);
                    seen.add(drawn.bar());
                    seen.add(drawn.extreme() == null ? drawn.bar() : drawn.extreme());
                    glows += drawn.glow() ? 1 : 0;
                    snortsOnExtremes += drawn.bar() == Kind.BULL_SNORT && drawn.extreme() != null ? 1 : 0;
                    assertDrawn(drawn, days.get(i).volume(), rows.get(i), chart + " " + stock.getKey() + " "
                            + days.get(i).date());
                }
            }
        }
        assertEquals(EnumSet.allOf(Kind.class), seen, "every rule had a day to show");
        assertTrue(glows > 0, "and a glow");
        assertTrue(snortsOnExtremes > 0, "and a bull snort on a 3- or 12-month extreme");
    }

    @Test
    @DisplayName("in Weekly, CAN SLIM's volume bars follow the same rules with a week for a day, over 2.5 years of weeks")
    void weeklyBarsByTheSameRules() {
        Random random = new Random(5);
        // Every ninth week trades 6 times as much, so that some weeks reach 3 times the 50-week average; one stock's
        // volume rising (new highs), the other's falling (new lows).
        Map<String, List<Session>> stocks = new LinkedHashMap<>();
        stocks.put("RISING", sessions(650,
                i -> Math.round((100_000 + 600 * i) * (0.5 + random.nextDouble()) * ((i / 5) % 9 == 4 ? 6 : 1)), random));
        stocks.put("FALLING", sessions(650,
                i -> Math.round((400_000 - 450 * i) * (0.5 + random.nextDouble()) * ((i / 5) % 9 == 4 ? 6 : 1)), random));
        Set<Kind> seen = EnumSet.noneOf(Kind.class);
        int glows = 0;
        for (Map.Entry<String, List<Session>> stock : stocks.entrySet()) {
            store(stock.getKey(), stock.getValue());
            List<Session> weeks = weeks(stock.getValue());
            List<Map<String, Double>> rows = rows(CANSLIM, stock.getKey(), "weekly");
            assertEquals(weeks.size(), rows.size(), stock.getKey() + ": a bar a week");
            for (int i = 0; i < weeks.size(); i++) {
                Session week = weeks.get(i);
                String what = stock.getKey() + ", the week of " + week.date();
                assertEquals(epoch(week.date()), rows.get(i).get("time").longValue(), what + ": at its first session");
                Drawn drawn = expected(weeks, i);
                seen.add(drawn.bar());
                seen.add(drawn.extreme() == null ? drawn.bar() : drawn.extreme());
                glows += drawn.glow() ? 1 : 0;
                assertDrawn(drawn, week.volume(), rows.get(i), what);
            }
        }
        assertEquals(EnumSet.allOf(Kind.class), seen, "every rule had a week to show");
        assertTrue(glows > 0, "and a glow");
    }

    /** The days as weeks, Monday to Sunday: the first open, the highest high, the lowest low, the last close. */
    private static List<Session> weeks(List<Session> days) {
        Map<LocalDate, List<Session>> byMonday = new LinkedHashMap<>();
        for (Session day : days) {
            LocalDate monday = day.date().minusDays(day.date().getDayOfWeek().getValue() - 1);
            byMonday.computeIfAbsent(monday, m -> new ArrayList<>()).add(day);
        }
        List<Session> weeks = new ArrayList<>();
        for (List<Session> week : byMonday.values()) {
            weeks.add(new Session(week.getFirst().date(), week.getFirst().open(),
                    week.stream().mapToDouble(Session::high).max().orElseThrow(),
                    week.stream().mapToDouble(Session::low).min().orElseThrow(),
                    week.getLast().close(), week.stream().mapToLong(Session::volume).sum()));
        }
        return weeks;
    }

    @Test
    @DisplayName("the two heaviest and the two lightest of the last 20 sessions, the day included, are colored")
    void twoHeaviestAndTwoLightestOfTwenty() {
        Map<Integer, Long> volumes = Map.of(5, 9000L, 30, 5000L, 31, 4000L, 32, 3000L, 35, 100L, 36, 200L, 37, 300L);
        List<Session> days = withVolumes(flat(40), volumes);
        store("STOCK", days);

        for (Path chart : CHARTS.keySet()) {
            List<Map<String, Double>> rows = rows(chart, "STOCK");
            assertDrawn(Kind.UP, 9000, rows.get(5), "twenty sessions are needed before any comparison");
            assertDrawn(Kind.HEAVY, 5000, rows.get(30), "the heaviest of twenty");
            assertDrawn(Kind.HEAVY, 4000, rows.get(31), "the second heaviest");
            assertDrawn(Kind.UP, 3000, rows.get(32), "the third is an ordinary day");
            assertDrawn(Kind.LIGHT, 100, rows.get(35), "the lightest");
            assertDrawn(Kind.LIGHT, 200, rows.get(36), "the second lightest");
            assertDrawn(Kind.UP, 300, rows.get(37), "the third is an ordinary day");
        }
    }

    @Test
    @DisplayName("Q marks the highest or lowest volume since the same date three months back, once the bars go back that far")
    void quarterHighsAndLows() {
        Map<LocalDate, Long> marked = Map.of(
                LocalDate.of(2026, 4, 2), 9000L,
                LocalDate.of(2026, 4, 6), 9500L,
                LocalDate.of(2026, 5, 4), 10L,
                LocalDate.of(2026, 7, 2), 9200L,
                LocalDate.of(2026, 7, 6), 9300L);
        List<Session> days = withVolumes(flat(150), marked);
        store("STOCK", days);

        for (Path chart : CHARTS.keySet()) {
            Map<LocalDate, Map<String, Double>> rows = byDate(days, rows(chart, "STOCK"));
            assertDrawn(Kind.HEAVY, 9000, rows.get(LocalDate.of(2026, 4, 2)),
                    "the heaviest yet, but the bars start 5 January: not three months before 2 April");
            assertDrawn(Kind.QUARTER_HIGH, 9500, rows.get(LocalDate.of(2026, 4, 6)), "three months of bars");
            assertDrawn(Kind.QUARTER_LOW, 10, rows.get(LocalDate.of(2026, 5, 4)), "the lightest in three months");
            assertDrawn(Kind.HEAVY, 9200, rows.get(LocalDate.of(2026, 7, 2)),
                    "6 April's 9,500 is less than three months back");
            assertDrawn(Kind.QUARTER_HIGH, 9300, rows.get(LocalDate.of(2026, 7, 6)),
                    "6 April is exactly three months back: out of the window");
        }
    }

    @Test
    @DisplayName("Y marks the highest or lowest volume of the last 12 months, and wins over Q")
    void yearHighsAndLows() {
        Map<LocalDate, Long> marked = Map.of(
                LocalDate.of(2026, 3, 2), 9000L,
                LocalDate.of(2027, 1, 4), 8000L,
                LocalDate.of(2027, 1, 5), 8500L,
                LocalDate.of(2027, 2, 1), 9100L,
                LocalDate.of(2027, 2, 8), 5L);
        List<Session> days = withVolumes(flat(300), marked);
        store("STOCK", days);

        for (Path chart : CHARTS.keySet()) {
            Map<LocalDate, Map<String, Double>> rows = byDate(days, rows(chart, "STOCK"));
            assertDrawn(Kind.QUARTER_HIGH, 8000, rows.get(LocalDate.of(2027, 1, 4)),
                    "the year since 4 January 2026 reaches before the first bar: a Q, not a Y");
            assertDrawn(Kind.QUARTER_HIGH, 8500, rows.get(LocalDate.of(2027, 1, 5)),
                    "a year of bars now, but 2 March's 9,000 is within it");
            assertDrawn(Kind.YEAR_HIGH, 9100, rows.get(LocalDate.of(2027, 2, 1)), "the heaviest of the year");
            assertDrawn(Kind.YEAR_LOW, 5, rows.get(LocalDate.of(2027, 2, 8)), "the lightest of the year");
        }
    }

    @Test
    @DisplayName("an ordinary day's bar is red when it closed below the previous day's close, else green, whatever its candle")
    void ordinaryDaysAgainstThePreviousClose() {
        store("STOCK", List.of(
                day(FIRST, 101, 100, 1000),
                day(FIRST.plusDays(1), 99, 100.5, 1000),
                day(FIRST.plusDays(2), 102, 101, 1000),
                day(FIRST.plusDays(3), 99, 100, 1000),
                day(FIRST.plusDays(4), 99, 100, 1000)));

        for (Path chart : CHARTS.keySet()) {
            List<Map<String, Double>> rows = rows(chart, "STOCK");
            assertDrawn(Kind.DOWN, 1000, rows.get(0), "the first bar has no previous close: below its own open");
            assertDrawn(Kind.UP, 1000, rows.get(1), "above the previous close");
            assertDrawn(Kind.UP, 1000, rows.get(2), "a red candle, but above the previous close");
            assertDrawn(Kind.DOWN, 1000, rows.get(3), "a green candle, but below the previous close");
            assertDrawn(Kind.UP, 1000, rows.get(4), "unchanged from the previous close");
        }
    }

    @Test
    @DisplayName("a bull snort is white: a range of 5% of the previous close, a close in its top third and above the previous close")
    void bullSnorts() {
        // Fifty quiet sessions first, for the volume's average; each candidate on ten times their volume, so heavy
        // (one of the two heaviest of 20, the ties included) when it is not a snort.
        List<LocalDate> d = weekdays(61);
        List<Session> days = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            days.add(quiet(d.get(i)));
        }
        days.add(new Session(d.get(50), 100, 105, 100, 104, 10_000));
        days.add(quiet(d.get(51)));
        days.add(new Session(d.get(52), 100, 104.9, 100, 104.9, 10_000));
        days.add(quiet(d.get(53)));
        days.add(new Session(d.get(54), 100, 106, 100, 104, 10_000));
        days.add(quiet(d.get(55)));
        days.add(new Session(d.get(56), 100, 106, 100, 103.9, 10_000));
        days.add(quiet(d.get(57)));
        days.add(new Session(d.get(58), 95, 101, 95, 100, 10_000));
        days.add(quiet(d.get(59)));
        days.add(new Session(d.get(60), 100, 110, 100, 109, 10_000));
        store("STOCK", days);

        for (Path chart : CHARTS.keySet()) {
            List<Map<String, Double>> rows = rows(chart, "STOCK");
            assertDrawn(Kind.BULL_SNORT, 10_000, rows.get(50), "a range of exactly 5%, closing 80% up it");
            assertDrawn(Kind.HEAVY, 10_000, rows.get(52), "a range of 4.9%");
            assertDrawn(Kind.BULL_SNORT, 10_000, rows.get(54), "closing exactly two thirds up its range");
            assertDrawn(Kind.HEAVY, 10_000, rows.get(56), "closing just under the top third");
            assertDrawn(Kind.HEAVY, 10_000, rows.get(58), "closing level with the previous close, not above it");
            assertDrawn(Kind.BULL_SNORT, 10_000, rows.get(60), "a range of 10%, closing near the high");
        }
    }

    @Test
    @DisplayName("a bull snort needs at least 3 times the average volume of the 50 sessions before it")
    void bullSnortVolume() {
        Map<String, List<Session>> stocks = Map.of(
                "THREE", afterQuiet(50, 3000),
                "UNDER", afterQuiet(50, 2999),
                "SHORT", afterQuiet(49, 100_000));
        stocks.forEach(this::store);

        for (Path chart : CHARTS.keySet()) {
            assertDrawn(Kind.BULL_SNORT, 3000, rows(chart, "THREE").get(50), "exactly 3 times the average of 1,000");
            assertDrawn(Kind.HEAVY, 2999, rows(chart, "UNDER").get(50), "just under 3 times: heavy only");
            assertDrawn(Kind.HEAVY, 100_000, rows(chart, "SHORT").get(49),
                    "100 times the volume, but only 49 sessions before it to average");
        }
    }

    @Test
    @DisplayName("white wins over light blue and over a Q's bar color, and the Q dot stays")
    void bullSnortOverOtherColors() {
        LocalDate heavy = LocalDate.of(2026, 3, 16);
        LocalDate quarter = LocalDate.of(2026, 4, 6);
        List<Session> days = new ArrayList<>();
        for (Session day : flat(80)) {
            boolean snort = day.date().equals(heavy) || day.date().equals(quarter);
            days.add(!snort ? day : new Session(day.date(), 101, 107, 101, 106.5,
                    day.date().equals(heavy) ? 9000 : 9500));
        }
        store("STOCK", days);

        for (Path chart : CHARTS.keySet()) {
            Map<LocalDate, Map<String, Double>> rows = byDate(days, rows(chart, "STOCK"));
            assertDrawn(new Drawn(Kind.BULL_SNORT, null, false), 9000, rows.get(heavy), "the heaviest of 20 days");
            assertDrawn(new Drawn(Kind.BULL_SNORT, Kind.QUARTER_HIGH, false), 9500, rows.get(quarter),
                    "the heaviest in three months: white, with its Q");
        }
    }

    @Test
    @DisplayName("a bull snort that is also the highest volume in 3 or 12 months shows both, not the bull snort alone")
    void bullSnortOnAnExtremeShowsBoth() {
        LocalDate year = weekdays(300).get(280);
        List<Session> days = new ArrayList<>();
        for (Session day : flat(300)) {
            days.add(day.date().equals(year) ? new Session(day.date(), 101, 107, 101, 106.5, 9500) : day);
        }
        store("YEAR", days);
        LocalDate quarter = LocalDate.of(2026, 4, 6);
        List<Session> shorter = new ArrayList<>();
        for (Session day : flat(80)) {
            shorter.add(day.date().equals(quarter) ? new Session(day.date(), 101, 107, 101, 106.5, 9500) : day);
        }
        store("QUARTER", shorter);

        for (Path chart : CHARTS.keySet()) {
            Map<String, Double> twelve = byDate(days, rows(chart, "YEAR")).get(year);
            assertDrawn(new Drawn(Kind.BULL_SNORT, Kind.YEAR_HIGH, false), 9500, twelve, chart + " 12 months");
            assertEquals(9500.0, twelve.get(BAR.get(Kind.BULL_SNORT)), "the white bar stays");
            assertEquals(9500.0, twelve.get(ALSO.get(Kind.YEAR_HIGH)), "and the 12-month high is named beside it");
            assertNull(twelve.get(ALSO.get(Kind.QUARTER_HIGH)), "a 12-month high is not also named a 3-month one");

            Map<String, Double> three = byDate(shorter, rows(chart, "QUARTER")).get(quarter);
            assertDrawn(new Drawn(Kind.BULL_SNORT, Kind.QUARTER_HIGH, false), 9500, three, chart + " 3 months");
            assertEquals(9500.0, three.get(BAR.get(Kind.BULL_SNORT)), "the white bar stays");
            assertEquals(9500.0, three.get(ALSO.get(Kind.QUARTER_HIGH)), "and the 3-month high is named beside it");
            assertNull(three.get(ALSO.get(Kind.YEAR_HIGH)), "bars do not go back a year: no Y");
        }
    }

    @Test
    @DisplayName("over 1 million shares and a close at least 4.95% above the previous close glow, whatever the bar")
    void heavyFivePercentUpDaysGlow() {
        List<LocalDate> dates = weekdays(60);
        Map<Integer, Session> marked = Map.of(
                22, new Session(dates.get(22), 107, 108, 106, 106.05, 1_000_001),
                25, new Session(dates.get(25), 107, 108, 106, 106.05, 1_000_000),
                28, new Session(dates.get(28), 107, 108, 105, 105.9995, 2_000_000),
                31, new Session(dates.get(31), 107, 108, 105, 105.98, 2_000_000),
                55, new Session(dates.get(55), 100, 109, 100, 108, 3_000_000));
        List<Session> days = new ArrayList<>(flat(60));
        marked.forEach(days::set);
        store("STOCK", days);

        for (Path chart : CHARTS.keySet()) {
            List<Map<String, Double>> rows = rows(chart, "STOCK");
            assertDrawn(new Drawn(Kind.HEAVY, null, true), 1_000_001, rows.get(22),
                    "exactly 5% above the previous close of 101");
            assertDrawn(new Drawn(Kind.HEAVY, null, false), 1_000_000, rows.get(25), "1 million is not beyond it");
            assertDrawn(new Drawn(Kind.HEAVY, null, true), 2_000_000, rows.get(28),
                    "exactly 4.95% up, as a 5% upper circuit rounded to the tick can close");
            assertDrawn(new Drawn(Kind.HEAVY, null, false), 2_000_000, rows.get(31), "4.93% up");
            assertDrawn(new Drawn(Kind.BULL_SNORT, null, true), 3_000_000, rows.get(55), "a bull snort glows too");
        }
    }

    @Test
    @DisplayName("the colored bars share the volume's scale, the panel's own volume bars are hidden, the candles keep their colors")
    void chartConfiguration() throws IOException {
        store("STOCK", flat(3));
        List<JsonNode> volumeOverrides = new ArrayList<>();
        for (Map.Entry<Path, Integer> chart : CHARTS.entrySet()) {
            JsonNode panel = panel(chart.getKey(), chart.getValue());
            String where = chart.getKey().getFileName() + " panel " + chart.getValue();
            JsonNode options = panel.path("options");
            assertEquals("candles+volume", options.path("mode").asText(), where);
            assertEquals("open-close", options.path("colorStrategy").asText(), where);
            assertEquals("green", options.path("colors").path("up").asText(), where);
            assertEquals("red", options.path("colors").path("down").asText(), where);

            Map<String, Map<String, JsonNode>> overrides = overrides(panel);
            Map<String, JsonNode> volume = overrides.get("volume");
            double opacity = volume.get("custom.fillOpacity").asDouble();
            assertTrue(opacity > 0 && opacity < 0.01, where + ": the panel takes its volume bars' alpha from it, "
                    + "and 0 would mean its default of 50%");

            List<String> columns = new ArrayList<>(rows(chart.getKey(), "STOCK").getFirst().keySet());
            List<String> series = new ArrayList<>(List.of(GLOW));
            series.addAll(BAR.values());
            series.addAll(ALSO.values());
            series.add(Q);
            series.add(Y);
            for (String name : series) {
                Map<String, JsonNode> properties = overrides.get(name);
                assertNotNull(properties, where + ": " + name);
                assertTrue(columns.indexOf("volume") < columns.indexOf(name), where + ": the volume comes first, "
                        + "so the scale they share is ranged for it, low in the chart");
                for (String shared : List.of("unit", "custom.axisPlacement", "custom.axisLabel")) {
                    assertEquals(volume.get(shared), properties.get(shared), where + ": " + name + " " + shared);
                }
                for (String apart : List.of("min", "max", "custom.axisSoftMin", "custom.axisSoftMax",
                        "custom.scaleDistribution")) {
                    assertFalse(properties.containsKey(apart), where + ": " + name + " would get a scale of its own");
                }
                volumeOverrides.add(JsonMapper.INSTANCE.valueToTree(properties));
            }
            for (Map.Entry<Kind, String> bar : BAR.entrySet()) {
                Map<String, JsonNode> properties = overrides.get(bar.getValue());
                assertEquals("bars", properties.get("custom.drawStyle").asText(), bar.getValue());
                assertEquals(COLOR.get(bar.getKey()), properties.get("color").path("fixedColor").asText(),
                        bar.getValue());
                boolean special = bar.getKey().compareTo(Kind.QUARTER_HIGH) >= 0;
                assertEquals(special, properties.get("custom.hideFrom").path("legend").asBoolean(),
                        bar.getValue() + ": the legend names the dots instead");
                assertTrue(columns.indexOf(GLOW) < columns.indexOf(bar.getValue()),
                        where + ": the glow is drawn first, behind the bars");
            }
            for (Map.Entry<Kind, String> also : ALSO.entrySet()) {
                Map<String, JsonNode> properties = overrides.get(also.getValue());
                assertEquals("bars", properties.get("custom.drawStyle").asText(), also.getValue());
                assertEquals(COLOR.get(also.getKey()), properties.get("color").path("fixedColor").asText(),
                        also.getValue() + ": the extreme's own color");
                assertTrue(properties.get("custom.barWidthFactor").asDouble() < 0.6,
                        also.getValue() + ": a core, narrower than the bars (0.6) it sits on");
                assertTrue(properties.get("custom.hideFrom").path("legend").asBoolean(),
                        also.getValue() + ": the legend names the dots instead");
                assertFalse(properties.get("custom.hideFrom").path("tooltip").asBoolean(),
                        also.getValue() + ": its line in the tooltip is the point");
                assertTrue(columns.indexOf(BAR.get(Kind.BULL_SNORT)) < columns.indexOf(also.getValue()),
                        where + ": " + also.getValue() + " is drawn after the white bar, so on it");
            }
            for (String marker : List.of(Q, Y)) {
                assertEquals("points", overrides.get(marker).get("custom.drawStyle").asText(), marker);
                assertTrue(overrides.get(marker).get("custom.hideFrom").path("tooltip").asBoolean(),
                        marker + ": its height is only where the dot sits");
            }
            Map<String, JsonNode> glow = overrides.get(GLOW);
            assertEquals("bars", glow.get("custom.drawStyle").asText());
            assertTrue(glow.get("custom.barWidthFactor").asDouble() > 0.6, "wider than the bars (0.6)");
            assertTrue(glow.get("custom.fillOpacity").asDouble() < 100, "see-through");
            assertFalse(glow.get("custom.hideFrom").path("legend").asBoolean(), "named in the legend");
            assertEquals("yes", glow.get("mappings").path(0).path("options").path("result").path("text").asText(),
                    "its height only places it, so the tooltip says yes");
        }
        int half = volumeOverrides.size() / 2;
        assertEquals(volumeOverrides.subList(0, half), volumeOverrides.subList(half, volumeOverrides.size()),
                "both charts color the volume alike");
    }

    /** What the user asked for, written out apart from the SQL. */
    private static Drawn expected(List<Session> sessions, int at) {
        Session day = sessions.get(at);
        Double previous = at == 0 ? null : sessions.get(at - 1).close();
        Kind extreme = extreme(sessions, at);
        boolean glow = previous != null && day.volume() > 1_000_000
                && percent(day.close() - previous, previous) >= 4.95;
        Kind bar;
        if (previous != null && day.close() > previous && percent(day.high() - day.low(), previous) >= 5
                && day.close() - day.low() >= 2.0 / 3 * (day.high() - day.low()) - 1e-9
                && atLeastThreeTimesTheAverage(sessions, at)) {
            bar = Kind.BULL_SNORT;
        } else if (extreme != null) {
            bar = extreme;
        } else if (at >= 19 && day.volume() >= percentile(last20(sessions, at), 0.9)) {
            bar = Kind.HEAVY;
        } else if (at >= 19 && day.volume() <= percentile(last20(sessions, at), 0.1)) {
            bar = Kind.LIGHT;
        } else {
            bar = day.close() < (previous == null ? day.open() : previous) ? Kind.DOWN : Kind.UP;
        }
        return new Drawn(bar, extreme, glow);
    }

    /** A 12-month extreme over a 3-month one, each only once the bars reach back that far. */
    private static Kind extreme(List<Session> sessions, int at) {
        Session day = sessions.get(at);
        LocalDate first = sessions.getFirst().date();
        LocalDate year = day.date().minusMonths(12);
        LocalDate quarter = day.date().minusMonths(3);
        if (!first.isAfter(year)) {
            if (day.volume() == extreme(sessions, at, year, true)) {
                return Kind.YEAR_HIGH;
            }
            if (day.volume() == extreme(sessions, at, year, false)) {
                return Kind.YEAR_LOW;
            }
        }
        if (!first.isAfter(quarter)) {
            if (day.volume() == extreme(sessions, at, quarter, true)) {
                return Kind.QUARTER_HIGH;
            }
            if (day.volume() == extreme(sessions, at, quarter, false)) {
                return Kind.QUARTER_LOW;
            }
        }
        return null;
    }

    /** As a percentage of {@code of}, to the sixth place, as a person reading "5%" means it. */
    private static double percent(double move, double of) {
        return Math.round(move / of * 100 * 1e6) / 1e6;
    }

    /** The highest (or lowest) volume after {@code from}, up to and including the session {@code at}. */
    private static long extreme(List<Session> sessions, int at, LocalDate from, boolean highest) {
        long best = sessions.get(at).volume();
        for (int i = 0; i <= at; i++) {
            if (sessions.get(i).date().isAfter(from)) {
                long volume = sessions.get(i).volume();
                best = highest ? Math.max(best, volume) : Math.min(best, volume);
            }
        }
        return best;
    }

    /** The day's volume at least 3 times the average of the 50 sessions before it, which must all be there. */
    private static boolean atLeastThreeTimesTheAverage(List<Session> sessions, int at) {
        if (at < 50) {
            return false;
        }
        long total = sessions.subList(at - 50, at).stream().mapToLong(Session::volume).sum();
        return sessions.get(at).volume() * 50 >= 3 * total;
    }

    private static List<Long> last20(List<Session> sessions, int at) {
        return sessions.subList(at - 19, at + 1).stream().map(Session::volume).toList();
    }

    /** As numpy's percentile and Excel's PERCENTILE: linear between the two nearest ranks. */
    private static double percentile(List<Long> values, double p) {
        List<Long> sorted = values.stream().sorted().toList();
        double rank = p * (sorted.size() - 1);
        int below = (int) Math.floor(rank);
        int above = Math.min(below + 1, sorted.size() - 1);
        return sorted.get(below) + (sorted.get(above) - sorted.get(below)) * (rank - below);
    }

    private static void assertDrawn(Kind kind, long volume, Map<String, Double> row, String message) {
        boolean extreme = kind.compareTo(Kind.QUARTER_HIGH) >= 0;
        assertDrawn(new Drawn(kind, extreme ? kind : null, false), volume, row, message);
    }

    /** The day's one bar is the kind's, at the day's volume; Q or Y sits above it, and the glow behind, on their days. */
    private static void assertDrawn(Drawn drawn, long volume, Map<String, Double> row, String message) {
        assertNotNull(row, message);
        for (Map.Entry<Kind, String> bar : BAR.entrySet()) {
            Double value = row.get(bar.getValue());
            if (bar.getKey() == drawn.bar()) {
                assertNotNull(value, message + ": should be " + bar.getValue());
                assertEquals(volume, value, 1e-9, message + ": " + bar.getValue());
            } else {
                assertNull(value, message + ": not " + bar.getValue() + " but " + BAR.get(drawn.bar()));
            }
        }
        Kind extreme = drawn.extreme();
        for (Map.Entry<Kind, String> also : ALSO.entrySet()) {
            Double value = row.get(also.getValue());
            if (drawn.bar() == Kind.BULL_SNORT && extreme == also.getKey()) {
                assertNotNull(value, message + ": a bull snort on an extreme should also be " + also.getValue());
                assertEquals(volume, value, 1e-9, message + ": " + also.getValue());
            } else {
                assertNull(value, message + ": not " + also.getValue() + ", the day is a " + BAR.get(drawn.bar()));
            }
        }
        boolean quarter = extreme == Kind.QUARTER_HIGH || extreme == Kind.QUARTER_LOW;
        boolean year = extreme == Kind.YEAR_HIGH || extreme == Kind.YEAR_LOW;
        for (Map.Entry<String, Boolean> above : Map.of(Q, quarter, Y, year, GLOW, drawn.glow()).entrySet()) {
            Double at = row.get(above.getKey());
            if (above.getValue()) {
                assertTrue(at != null && at > volume, message + ": " + above.getKey() + " above the bar");
            } else {
                assertNull(at, message + ": no " + above.getKey());
            }
        }
    }

    /** A candle from its open and close, its high and low a rupee either side. */
    private static Session day(LocalDate date, double open, double close, long volume) {
        return new Session(date, open, Math.max(open, close) + 1, Math.min(open, close) - 1, close, volume);
    }

    /** Weekdays from 5 January 2026, each candle 100 to 101, volumes all different within any 100 sessions. */
    private static List<Session> flat(int count) {
        List<Session> days = new ArrayList<>();
        List<LocalDate> dates = weekdays(count);
        for (int i = 0; i < count; i++) {
            days.add(day(dates.get(i), 100, 101, 1000 + (i * 37L) % 100));
        }
        return days;
    }

    /** A quiet session: 99.50 to 100.50, closing at 100 on 1,000 shares. */
    private static Session quiet(LocalDate date) {
        return new Session(date, 100, 100.5, 99.5, 100, 1000);
    }

    /** {@code count} quiet sessions, then a bull snort's candle (range 6%, closing 5% up) on the volume given. */
    private static List<Session> afterQuiet(int count, long volume) {
        List<LocalDate> dates = weekdays(count + 1);
        List<Session> days = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            days.add(quiet(dates.get(i)));
        }
        days.add(new Session(dates.get(count), 100, 106, 100, 105, volume));
        return days;
    }

    private static List<Session> withVolumes(List<Session> days, Map<?, Long> volumes) {
        List<Session> out = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            Session day = days.get(i);
            Long volume = volumes.containsKey(i) ? volumes.get(i) : volumes.get(day.date());
            out.add(volume == null ? day
                    : new Session(day.date(), day.open(), day.high(), day.low(), day.close(), volume));
        }
        int found = (int) out.stream().filter(day -> volumes.containsValue(day.volume())).count();
        assertEquals(volumes.size(), found, "every date given is a weekday in the range");
        return out;
    }

    /** Weekdays from 5 January 2026, opening and closing at whole rupees from 100 to 107, with the volumes given. */
    private static List<Session> sessions(int count, IntToLongFunction volume, Random random) {
        List<Session> days = new ArrayList<>();
        List<LocalDate> dates = weekdays(count);
        for (int i = 0; i < count; i++) {
            days.add(day(dates.get(i), 100 + random.nextInt(8), 100 + random.nextInt(8), volume.applyAsLong(i)));
        }
        return days;
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

    private static Map<LocalDate, Map<String, Double>> byDate(List<Session> days, List<Map<String, Double>> rows) {
        assertEquals(days.size(), rows.size());
        Map<LocalDate, Map<String, Double>> out = new LinkedHashMap<>();
        for (int i = 0; i < days.size(); i++) {
            assertEquals(epoch(days.get(i).date()), rows.get(i).get("time").longValue(), "oldest first");
            out.put(days.get(i).date(), rows.get(i));
        }
        return out;
    }

    private void store(String symbol, List<Session> days) {
        long instrument = instruments.findOrCreate(symbol, "NSE", "CASH");
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO daily_bar (instrument_id, session_date, ts_epoch, open, high, low, close, volume)
                    VALUES (?,?,?,?,?,?,?,?)""")) {
                for (Session day : days) {
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

    private static JsonNode panel(Path dashboard, int id) throws IOException {
        for (JsonNode panel : JsonMapper.INSTANCE.readTree(Files.readString(dashboard)).path("panels")) {
            if (panel.path("id").asInt() == id) {
                return panel;
            }
        }
        throw new AssertionError(dashboard + " has no panel " + id);
    }

    /** Each by-name override's properties, by id. */
    private static Map<String, Map<String, JsonNode>> overrides(JsonNode panel) {
        Map<String, Map<String, JsonNode>> out = new LinkedHashMap<>();
        for (JsonNode override : panel.path("fieldConfig").path("overrides")) {
            if (override.path("matcher").path("id").asText().equals("byName")) {
                Map<String, JsonNode> properties = new LinkedHashMap<>();
                override.path("properties").forEach(p -> properties.put(p.path("id").asText(), p.path("value")));
                out.put(override.path("matcher").path("options").asText(), properties);
            }
        }
        return out;
    }

    /** The chart's rows for the symbol, in daily candles. */
    private List<Map<String, Double>> rows(Path dashboard, String symbol) {
        return rows(dashboard, symbol, "daily");
    }

    /** The chart's rows for the symbol, oldest first, each column by the name the chart shows; CAN SLIM's in a frame. */
    private List<Map<String, Double>> rows(Path dashboard, String symbol, String frame) {
        String sql;
        try {
            sql = panel(dashboard, CHARTS.get(dashboard)).path("targets").get(0).path("rawQueryText").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
