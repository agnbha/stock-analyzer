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
 * above the 90th percentile of the last 20 sessions, yellow at or below the 10th; the highest or lowest volume of the
 * last 3 months gets a Q dot above its bar, of the last 12 months a Y dot. The candles keep their colors. The volumes
 * and prices are invented.
 */
class VolumeColorsTest {

    private static final Path TREND = Path.of("grafana", "dashboards", "04-stock-trend.json");
    private static final Path CANSLIM = Path.of("grafana", "dashboards", "06-canslim.json");
    private static final Map<Path, Integer> CHARTS = Map.of(TREND, 10, CANSLIM, 20);
    private static final LocalDate FIRST = LocalDate.of(2026, 1, 5);

    private static final String Q = "Q · highest or lowest in 3 months";
    private static final String Y = "Y · highest or lowest in 12 months";

    private enum Kind { DOWN, UP, HEAVY, LIGHT, QUARTER_HIGH, QUARTER_LOW, YEAR_HIGH, YEAR_LOW }

    /** Each kind of day's bar series, and its color. */
    private static final Map<Kind, String> BAR = new EnumMap<>(Map.of(
            Kind.DOWN, "Volume, price down",
            Kind.UP, "Volume, price up",
            Kind.HEAVY, "Volume ≥ 90th percentile of 20 sessions",
            Kind.LIGHT, "Volume ≤ 10th percentile of 20 sessions",
            Kind.QUARTER_HIGH, "Highest volume in 3 months",
            Kind.QUARTER_LOW, "Lowest volume in 3 months",
            Kind.YEAR_HIGH, "Highest volume in 12 months",
            Kind.YEAR_LOW, "Lowest volume in 12 months"));
    private static final Map<Kind, String> COLOR = new EnumMap<>(Map.of(
            Kind.DOWN, "red", Kind.UP, "green", Kind.HEAVY, "light-blue", Kind.LIGHT, "yellow",
            Kind.QUARTER_HIGH, "light-blue", Kind.QUARTER_LOW, "yellow",
            Kind.YEAR_HIGH, "light-blue", Kind.YEAR_LOW, "yellow"));

    private record Session(LocalDate date, double open, double close, long volume) {
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
        List<Session> rising = sessions(320, i -> Math.round((1000 + 30 * i) * (0.5 + random.nextDouble())), random);
        List<Session> ties = sessions(320,
                i -> 500 * Math.round((9000 - 20 * i) * (0.5 + random.nextDouble()) / 500), random);
        store("RISING", rising);
        store("TIES", ties);
        Set<Kind> seen = EnumSet.noneOf(Kind.class);

        for (Map.Entry<String, List<Session>> stock : Map.of("RISING", rising, "TIES", ties).entrySet()) {
            List<Session> days = stock.getValue();
            for (Path chart : CHARTS.keySet()) {
                List<Map<String, Double>> rows = rows(chart, stock.getKey());
                assertEquals(days.size(), rows.size(), chart + " " + stock.getKey());
                for (int i = 0; i < days.size(); i++) {
                    Kind kind = expected(days, i);
                    seen.add(kind);
                    assertDrawn(kind, days.get(i).volume(), rows.get(i), chart + " " + stock.getKey() + " "
                            + days.get(i).date());
                }
            }
        }
        assertEquals(EnumSet.allOf(Kind.class), seen, "every rule had a day to show");
    }

    @Test
    @DisplayName("the two heaviest and the two lightest of the last 20 sessions, the day included, are colored")
    void twoHeaviestAndTwoLightestOfTwenty() {
        List<Session> days = flat(40);
        Map<Integer, Long> volumes = Map.of(5, 9000L, 30, 5000L, 31, 4000L, 32, 3000L, 35, 100L, 36, 200L, 37, 300L);
        days = withVolumes(days, volumes);
        store("STOCK", days);

        for (Path chart : CHARTS.keySet()) {
            List<Map<String, Double>> rows = rows(chart, "STOCK");
            assertDrawn(Kind.UP, 9000, rows.get(5), "twenty sessions are needed before any comparison: its candle's");
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
                new Session(FIRST, 101, 100, 1000),
                new Session(FIRST.plusDays(1), 99, 100.5, 1000),
                new Session(FIRST.plusDays(2), 102, 101, 1000),
                new Session(FIRST.plusDays(3), 99, 100, 1000),
                new Session(FIRST.plusDays(4), 99, 100, 1000)));

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
            List<String> series = new ArrayList<>(BAR.values());
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
            }
            for (String marker : List.of(Q, Y)) {
                assertEquals("points", overrides.get(marker).get("custom.drawStyle").asText(), marker);
                assertTrue(overrides.get(marker).get("custom.hideFrom").path("tooltip").asBoolean(),
                        marker + ": its height is only where the dot sits");
            }
        }
        int half = volumeOverrides.size() / 2;
        assertEquals(volumeOverrides.subList(0, half), volumeOverrides.subList(half, volumeOverrides.size()),
                "both charts color the volume alike");
    }

    /** What the user asked for, written out apart from the SQL. */
    private static Kind expected(List<Session> sessions, int at) {
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
        if (at >= 19) {
            List<Long> last20 = sessions.subList(at - 19, at + 1).stream().map(Session::volume).toList();
            if (day.volume() >= percentile(last20, 0.9)) {
                return Kind.HEAVY;
            }
            if (day.volume() <= percentile(last20, 0.1)) {
                return Kind.LIGHT;
            }
        }
        double previous = at == 0 ? day.open() : sessions.get(at - 1).close();
        return day.close() < previous ? Kind.DOWN : Kind.UP;
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

    /** As numpy's percentile and Excel's PERCENTILE: linear between the two nearest ranks. */
    private static double percentile(List<Long> values, double p) {
        List<Long> sorted = values.stream().sorted().toList();
        double rank = p * (sorted.size() - 1);
        int below = (int) Math.floor(rank);
        int above = Math.min(below + 1, sorted.size() - 1);
        return sorted.get(below) + (sorted.get(above) - sorted.get(below)) * (rank - below);
    }

    /** The day's one bar is the kind's, at the day's volume; Q or Y sits above it on the days that are theirs. */
    private static void assertDrawn(Kind kind, long volume, Map<String, Double> row, String message) {
        assertNotNull(row, message);
        for (Map.Entry<Kind, String> bar : BAR.entrySet()) {
            Double value = row.get(bar.getValue());
            if (bar.getKey() == kind) {
                assertEquals(volume, value, 1e-9, message + ": " + bar.getValue());
            } else {
                assertNull(value, message + ": not " + bar.getValue() + " but " + BAR.get(kind));
            }
        }
        boolean quarter = kind == Kind.QUARTER_HIGH || kind == Kind.QUARTER_LOW;
        boolean year = kind == Kind.YEAR_HIGH || kind == Kind.YEAR_LOW;
        for (Map.Entry<String, Boolean> marker : Map.of(Q, quarter, Y, year).entrySet()) {
            Double at = row.get(marker.getKey());
            if (marker.getValue()) {
                assertTrue(at != null && at > volume, message + ": " + marker.getKey() + " above the bar");
            } else {
                assertNull(at, message + ": no " + marker.getKey());
            }
        }
    }

    /** Weekdays from 5 January 2026, each candle up a rupee, volumes all different within any 100 sessions. */
    private static List<Session> flat(int count) {
        List<Session> days = new ArrayList<>();
        List<LocalDate> dates = weekdays(count);
        for (int i = 0; i < count; i++) {
            days.add(new Session(dates.get(i), 100, 101, 1000 + (i * 37L) % 100));
        }
        return days;
    }

    private static List<Session> withVolumes(List<Session> days, Map<?, Long> volumes) {
        List<Session> out = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            Session day = days.get(i);
            Long volume = volumes.containsKey(i) ? volumes.get(i) : volumes.get(day.date());
            out.add(volume == null ? day : new Session(day.date(), day.open(), day.close(), volume));
        }
        int found = (int) out.stream().filter(day -> volumes.containsValue(day.volume())).count();
        assertEquals(volumes.size(), found, "every date given is a weekday in the range");
        return out;
    }

    /** Weekdays from 5 January 2026, opening and closing at whole rupees (some unchanged), with the volumes given. */
    private static List<Session> sessions(int count, IntToLongFunction volume, Random random) {
        List<Session> days = new ArrayList<>();
        List<LocalDate> dates = weekdays(count);
        for (int i = 0; i < count; i++) {
            days.add(new Session(dates.get(i), 100 + random.nextInt(5), 100 + random.nextInt(5),
                    volume.applyAsLong(i)));
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
                    insert.setDouble(5, Math.max(day.open(), day.close()) + 1);
                    insert.setDouble(6, Math.min(day.open(), day.close()) - 1);
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

    /** The chart's rows for the symbol, oldest first, each column by the name the chart shows. */
    private List<Map<String, Double>> rows(Path dashboard, String symbol) {
        String sql;
        try {
            sql = panel(dashboard, CHARTS.get(dashboard)).path("targets").get(0).path("rawQueryText").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String query = sql.replace("$symbol", symbol);
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
