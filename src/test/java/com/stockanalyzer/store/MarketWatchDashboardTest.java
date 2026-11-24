package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.canslim.DailyBar;
import com.stockanalyzer.store.jdbc.SqliteDailyBarRepository;
import com.stockanalyzer.store.jdbc.SqliteIndexMemberRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.util.JsonMapper;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Market Watch, by running its own queries over daily bars and index members stored the way the CAN SLIM scan and
 * {@code CanSlimMain marketwatch} store them. The prices are invented.
 */
class MarketWatchDashboardTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");
    private static final MarketClock CLOCK = MarketClock.nse();

    @TempDir
    Path directory;

    private Database database;
    private SqliteInstrumentRepository instruments;
    private SqliteDailyBarRepository dailyBars;
    private SqliteIndexMemberRepository members;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instruments = new SqliteInstrumentRepository(database);
        dailyBars = new SqliteDailyBarRepository(database);
        members = new SqliteIndexMemberRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    /** Weekdays from {@code start}, {@code count} of them. */
    private static List<LocalDate> sessions(LocalDate start, int count) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = start; days.size() < count; day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
        }
        return days;
    }

    private void store(String symbol, List<DailyBar> bars) {
        long id = instruments.findOrCreate(symbol, "NSE", "CASH");
        dailyBars.upsert(id, bars, bar -> CLOCK.sessionOpenEpoch(bar.date()));
    }

    private static DailyBar bar(LocalDate day, double high, double low) {
        return new DailyBar(day, (high + low) / 2, high, low, (high + low) / 2, 1000);
    }

    private static JsonNode dashboard(String file) throws IOException {
        return JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARDS.resolve(file)));
    }

    private static String sql(int panel, String indices, LocalDate from) throws IOException {
        JsonNode found = StreamSupport.stream(dashboard("10-market-watch.json").path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == panel).findFirst().orElseThrow();
        String text = found.path("targets").get(0).path("rawQueryText").asText()
                .replace("${index:sqlstring}", indices)
                .replace("$__from/1000", String.valueOf(CLOCK.sessionOpenEpoch(from)));
        assertTrue(!text.contains("$"), "an unfilled variable would make this prove nothing: " + text);
        return text;
    }

    private List<Map<String, Object>> rows(String sql) {
        return database.read(connection -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        });
    }

    /**
     * The day as drawn: its bias background if it has one, and its net new highs - checking that the bar is green above
     * zero, red below and absent at zero.
     */
    private static String drawn(Map<String, Object> row) {
        int net = ((Number) row.get("Net new highs")).intValue();
        assertEquals(net > 0 ? Integer.valueOf(net) : null, intOrNull(row.get("More new highs")), "the green bar");
        assertEquals(net < 0 ? Integer.valueOf(net) : null, intOrNull(row.get("More new lows")), "the red bar");
        boolean positive = ((Number) row.get("Positive bias")).intValue() == 1;
        boolean negative = ((Number) row.get("Negative bias")).intValue() == 1;
        assertTrue(!(positive && negative), "one background a day");
        return (positive ? "Positive bias " : negative ? "Negative bias " : "") + net;
    }

    private static Integer intOrNull(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    /**
     * One member with a flat year (high 101, low 99) and then a script: four new highs, a day that only equals the
     * high, three new lows, a new high. Beside it, a member listed too recently to count, rising every day, and a stock
     * outside the index, rising too.
     */
    private List<LocalDate> scripted() {
        List<LocalDate> days = sessions(LocalDate.of(2025, 6, 2), 280 + 9);
        List<DailyBar> one = new ArrayList<>();
        for (int i = 0; i < 280; i++) {
            one.add(bar(days.get(i), 101, 99));
        }
        double[][] script = {{102, 100}, {103, 100}, {104, 100}, {105, 100}, {105, 100}, {100, 98}, {100, 97},
                {100, 96}, {106, 100}};
        for (int i = 0; i < script.length; i++) {
            one.add(bar(days.get(280 + i), script[i][0], script[i][1]));
        }
        store("ONE", one);
        List<DailyBar> rising = new ArrayList<>();
        List<DailyBar> outside = new ArrayList<>();
        for (int i = 200; i < days.size(); i++) {
            rising.add(bar(days.get(i), 100 + i, 90 + i));
        }
        for (int i = 0; i < days.size(); i++) {
            outside.add(bar(days.get(i), 100 + i, 90 + i));
        }
        store("NEWLY", rising);
        store("OUTSIDE", outside);
        members.replace("NIFTY TEST", 0, List.of("ONE", "NEWLY"), "NSE", "test", 1_795_000_000L);
        return days;
    }

    @Test
    @DisplayName("net new highs a session; a bias from the third session in a row on one side; an even day ends a run")
    void netNewHighsAndBias() throws IOException {
        List<LocalDate> days = scripted();

        List<Map<String, Object>> rows = rows(sql(3, "'NIFTY TEST'", days.get(270)));
        List<Map<String, Object>> last = rows.subList(rows.size() - 9, rows.size());

        assertEquals(List.of("1", "1", "Positive bias 1", "Positive bias 1", "0", "-1", "-1", "Negative bias -1", "1"),
                last.stream().map(MarketWatchDashboardTest::drawn).toList(),
                "a high that only equals the year's best is not a new one");
        assertEquals(CLOCK.sessionOpenEpoch(days.get(280)), ((Number) last.getFirst().get("time")).longValue());
        assertEquals(List.of(1, 1, 1, 1, 0, -1, -1, -1, 1), last.stream()
                .map(row -> ((Number) row.get("Net new highs")).intValue()).toList());
        assertEquals(100.0, ((Number) last.getLast().get("Net new highs, % of members")).doubleValue());
        assertEquals(0.2, ((Number) last.getLast().get("10-day average")).doubleValue(),
                "the last 10 sessions: 0, four up, 0, three down, one up");
        assertTrue(rows.stream().allMatch(row -> ((Number) row.get("Members with a year of bars")).intValue() == 1),
                "the member listed within the year, and the stock outside the index, are not counted");
        LocalDate firstCounted = LocalDate.ofEpochDay(((Number) rows.getFirst().get("time")).longValue() / 86_400);
        assertTrue(!firstCounted.isBefore(days.getFirst().plusDays(364)), "no day without a year behind it");
    }

    @Test
    @DisplayName("the year is 52 weeks: a high from longer ago does not stand in the way")
    void theYearIsFiftyTwoWeeks() throws IOException {
        // The peak is 380 days before the last session: inside what the query reads, outside the year it compares.
        LocalDate last = LocalDate.of(2026, 6, 17);
        List<LocalDate> days = sessions(last.minusDays(380), 400).stream().filter(day -> !day.isAfter(last)).toList();
        List<DailyBar> bars = new ArrayList<>();
        bars.add(bar(days.getFirst(), 200, 99));
        for (int i = 1; i < days.size(); i++) {
            bars.add(bar(days.get(i), days.get(i).equals(last) ? 102 : 101, 99));
        }
        store("PEAK", bars);
        members.replace("NIFTY PEAK", 0, List.of("PEAK"), "NSE", "test", 1);

        List<Map<String, Object>> rows = rows(sql(3, "'NIFTY PEAK'", last));

        assertEquals(CLOCK.sessionOpenEpoch(last), ((Number) rows.getLast().get("time")).longValue());
        assertEquals("1", drawn(rows.getLast()), "the 200 high is 380 days before the last day");
    }

    @Test
    @DisplayName("a session with as many highs as lows ends a run: two up, one even, one up is no bias")
    void anEvenDayEndsARun() throws IOException {
        List<LocalDate> days = sessions(LocalDate.of(2025, 6, 2), 284);
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < 280; i++) {
            bars.add(bar(days.get(i), 101, 99));
        }
        bars.add(bar(days.get(280), 102, 100));
        bars.add(bar(days.get(281), 103, 100));
        bars.add(bar(days.get(282), 103, 100));
        bars.add(bar(days.get(283), 104, 100));
        store("EVEN", bars);
        members.replace("NIFTY EVEN", 0, List.of("EVEN"), "NSE", "test", 1);

        List<Map<String, Object>> rows = rows(sql(3, "'NIFTY EVEN'", days.get(283)));

        assertEquals(List.of("1", "1", "0", "1"),
                rows.subList(rows.size() - 4, rows.size()).stream().map(MarketWatchDashboardTest::drawn).toList());
    }

    @Test
    @DisplayName("the chart counts as a brute-force count does, over random prices, with a run under way at the start")
    void matchesABruteForceCount() throws IOException {
        Random random = new Random(7);
        List<LocalDate> days = sessions(LocalDate.of(2024, 9, 2), 560);
        Map<String, List<DailyBar>> stocks = new TreeMap<>();
        for (int s = 0; s < 9; s++) {
            // Two listed later: one gains a year behind it within the days compared, one never does.
            int listedAt = s < 7 ? 0 : 150 + 60 * (s - 6) * (s - 6);
            double price = 100;
            List<DailyBar> bars = new ArrayList<>();
            for (int i = listedAt; i < days.size(); i++) {
                price *= 1 + (random.nextDouble() - 0.5) * 0.06;
                double high = Math.round(price * (1 + random.nextDouble() * 0.02) * 100) / 100.0;
                double low = Math.round(price * (1 - random.nextDouble() * 0.02) * 100) / 100.0;
                bars.add(bar(days.get(i), high, low));
            }
            stocks.put("R" + s, bars);
            store("R" + s, bars);
        }
        members.replace("NIFTY RANDOM", 0, List.copyOf(stocks.keySet()), "NSE", "test", 1);
        LocalDate from = days.get(400);

        List<String> got = rows(sql(3, "'NIFTY RANDOM'", from)).stream()
                .map(row -> LocalDate.ofEpochDay(((Number) row.get("time")).longValue() / 86_400) + " " + drawn(row)
                        + " highs " + row.get("New 52-week highs") + " lows " + row.get("New 52-week lows")
                        + " of " + row.get("Members with a year of bars") + " average " + row.get("10-day average"))
                .toList();

        LocalDate start = from.minusDays(36);
        List<String> expected = new ArrayList<>();
        int side = 2;
        int run = 0;
        List<Integer> nets = new ArrayList<>();
        for (LocalDate day : days) {
            if (day.isBefore(start)) {
                continue;
            }
            int highs = 0;
            int lows = 0;
            int counted = 0;
            for (List<DailyBar> bars : stocks.values()) {
                DailyBar today = bars.stream().filter(b -> b.date().equals(day)).findFirst().orElse(null);
                if (today == null || ChronoUnit.DAYS.between(bars.getFirst().date(), day) < 364) {
                    continue;
                }
                counted++;
                List<DailyBar> year = bars.stream().filter(b -> b.date().isBefore(day)
                        && ChronoUnit.DAYS.between(b.date(), day) <= 364).toList();
                highs += today.high() > year.stream().mapToDouble(DailyBar::high).max().orElseThrow() ? 1 : 0;
                lows += today.low() < year.stream().mapToDouble(DailyBar::low).min().orElseThrow() ? 1 : 0;
            }
            int net = highs - lows;
            int now = Integer.signum(net);
            run = now == side ? run + 1 : 1;
            side = now;
            nets.add(net);
            Double average = nets.size() < 10 ? null
                    : nets.subList(nets.size() - 10, nets.size()).stream().mapToInt(Integer::intValue).sum() / 10.0;
            String bias = run >= 3 && now > 0 ? "Positive bias " : run >= 3 && now < 0 ? "Negative bias " : "";
            expected.add(day + " " + bias + net + " highs " + highs + " lows " + lows + " of " + counted
                    + " average " + average);
        }
        assertEquals(expected, got);
        assertTrue(got.stream().anyMatch(line -> line.contains("Positive bias"))
                && got.stream().anyMatch(line -> line.contains("Negative bias")), "both biases are exercised");
    }

    @Test
    @DisplayName("the summary: each index's latest session, its bias and its run, in the order the settings give")
    void summary() throws IOException {
        List<LocalDate> days = scripted();
        List<DailyBar> two = new ArrayList<>();
        for (int i = 0; i < 280; i++) {
            two.add(bar(days.get(i), 101, 99));
        }
        for (int i = 0; i < 4; i++) {
            two.add(bar(days.get(280 + i), 102 + i, 100));
        }
        store("TWO", two);
        members.replace("NIFTY UP", 0, List.of("TWO"), "NSE", "test", 1);
        members.reorder("NIFTY TEST", 1);

        List<Map<String, Object>> rows = rows(sql(2, "'NIFTY TEST','NIFTY UP'", days.getFirst()));

        assertEquals(List.of("NIFTY UP", "NIFTY TEST"), rows.stream().map(row -> row.get("Index")).toList());
        Map<String, Object> up = rows.get(0);
        assertEquals(Arrays.asList(format(days.get(283)), 1, 0.4, 1, 0, "Positive bias", "4 with more highs", 1),
                Arrays.asList(up.get("As of"), up.get("Net new highs"), up.get("10-day average"), up.get("New 52-week highs"),
                        up.get("New 52-week lows"), up.get("Bias"), up.get("Sessions in a row"), up.get("Members counted")));
        Map<String, Object> test = rows.get(1);
        assertEquals(Arrays.asList(format(days.get(288)), 1, 0.2, "None", "1 with more highs"),
                Arrays.asList(test.get("As of"), test.get("Net new highs"), test.get("10-day average"), test.get("Bias"),
                        test.get("Sessions in a row")));
    }

    private static String format(LocalDate day) {
        return day.getDayOfMonth() + " " + day.getMonth().getDisplayName(java.time.format.TextStyle.SHORT,
                java.util.Locale.ENGLISH) + " " + day.getYear();
    }

    @Test
    @DisplayName("the list: the members that made a new high or low on the latest session, against the year before")
    void latestHighsAndLows() throws IOException {
        List<LocalDate> days = scripted();

        List<Map<String, Object>> rows = rows(sql(4, "'NIFTY TEST'", days.getFirst()));

        assertEquals(1, rows.size(), "NEWLY has no year behind it; OUTSIDE is not a member");
        Map<String, Object> one = rows.getFirst();
        assertEquals(Arrays.asList("ONE", "High", 106.0, 105.0, null, null, "NIFTY TEST", days.get(288).toString()),
                Arrays.asList(one.get("Symbol"), one.get("New 52-week"), one.get("Day's high"),
                        one.get("Previous 52-week high"), one.get("Day's low"), one.get("Previous 52-week low"),
                        one.get("In"), one.get("Session")));
    }

    @Test
    @DisplayName("the index picker lists the indices in the settings' order; CAN SLIM and Stock Trend link here")
    void pickerAndLinks() throws IOException {
        members.replace("NIFTY SMALLCAP 250", 2, List.of("A"), "NSE", "test", 1);
        members.replace("NIFTY 50", 0, List.of("B"), "NSE", "test", 1);
        members.replace("SENSEX", 1, List.of("B"), "NSE", "settings", 1);
        JsonNode root = dashboard("10-market-watch.json");
        JsonNode variable = root.path("templating").path("list").get(0);
        assertEquals("index", variable.path("name").asText());
        assertEquals(List.of("NIFTY 50", "SENSEX", "NIFTY SMALLCAP 250"),
                rows(variable.path("query").asText()).stream().map(row -> row.get("index_name")).toList());
        JsonNode chart = root.path("panels").get(3);
        assertEquals("index", chart.path("repeat").asText(), "a chart for each index picked");

        for (String page : new String[]{"06-canslim.json", "04-stock-trend.json"}) {
            assertTrue(StreamSupport.stream(dashboard(page).path("links").spliterator(), false)
                            .anyMatch(link -> link.path("url").asText().equals("/d/stockanalyzer-marketwatch/market-watch")),
                    page + " links to Market Watch from its header");
        }
        List<String> back = StreamSupport.stream(root.path("links").spliterator(), false)
                .map(link -> link.path("url").asText()).toList();
        assertEquals(List.of("/d/stockanalyzer-canslim/can-slim", "/d/stockanalyzer-trend/stock-trend"), back);
    }
}
