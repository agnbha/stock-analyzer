package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.canslim.DailyBar;
import com.stockanalyzer.store.jdbc.SqliteDailyBarRepository;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Liquidity and Float, the page linked from Market Watch, by running its own queries over daily bars, company profiles
 * and shareholding patterns stored as the scan and the company-page reads store them. The figures are invented.
 */
class LiquidityFloatDashboardTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");
    private static final LocalDate LATEST = LocalDate.of(2026, 11, 23);

    @TempDir
    Path directory;

    private Database database;
    private SqliteInstrumentRepository instruments;
    private SqliteDailyBarRepository dailyBars;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instruments = new SqliteInstrumentRepository(database);
        dailyBars = new SqliteDailyBarRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    /** Weekdays back from {@code last}, {@code count} of them, oldest first. */
    private static List<LocalDate> sessionsTo(LocalDate last, int count) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = last; days.size() < count; day = day.minusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.addFirst(day);
            }
        }
        return days;
    }

    /** A stock trading at {@code close} on {@code volume} shares each day; the last session's bar unless {@code to}. */
    private long stock(String symbol, String exchange, List<LocalDate> days, double close, long volume) {
        long id = instruments.findOrCreate(symbol, exchange, "CASH");
        dailyBars.upsert(id, days.stream().map(d -> new DailyBar(d, close, close + 1, close - 1, close, volume)).toList(),
                bar -> MarketClock.nse().sessionOpenEpoch(bar.date()));
        return id;
    }

    private void execute(String sql, Object... parameters) {
        database.inTransaction(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                statement.executeUpdate();
            }
        });
    }

    private void holding(long instrument, String period, Double promoters, Double government, double fiis, double diis,
                         double pub) {
        execute("INSERT INTO company_holding (instrument_id, period, promoters, fiis, diis, government, public) "
                + "VALUES (?,?,?,?,?,?,?)", instrument, period, promoters, fiis, diis, government, pub);
    }

    private void marketCap(long instrument, double crore) {
        execute("INSERT INTO company_profile (instrument_id, fetched_at, market_cap_cr) VALUES (?, 1, ?)", instrument, crore);
    }

    private static JsonNode dashboard(String file) throws IOException {
        return JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARDS.resolve(file)));
    }

    private List<Map<String, Object>> panel(int id, int minValueCrore, int maxFloat) throws IOException {
        JsonNode found = StreamSupport.stream(dashboard("11-liquidity-float.json").path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == id).findFirst().orElseThrow();
        String sql = found.path("targets").get(0).path("rawQueryText").asText()
                .replace("$min_value_cr", String.valueOf(minValueCrore))
                .replace("$max_float_pct", String.valueOf(maxFloat));
        assertTrue(!sql.contains("$"), "an unfilled variable would make this prove nothing: " + sql);
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

    @Test
    @DisplayName("high liquidity: average volume times average close over three months, above the amount, most first")
    void highLiquidity() throws IOException {
        List<LocalDate> quarter = sessionsTo(LATEST, 60);
        // 100,000 shares at 1,000 a day: 10 crore.
        long big = stock("BIG", "NSE", quarter, 1_000, 100_000);
        marketCap(big, 50_000);
        holding(big, "2026-09", 40.0, 0.0, 30, 20, 10);
        // Exactly 8 crore a day is not above 8.
        stock("EIGHT", "NSE", quarter, 800, 100_000);
        // Only on BSE, 9 crore a day.
        stock("BSEONLY", "BSE", quarter, 900, 100_000);
        // Light for three months; heavy before them, which does not count.
        long faded = stock("FADED", "NSE", quarter, 100, 1_000);
        dailyBars.upsert(faded, sessionsTo(LATEST.minusMonths(4), 20).stream()
                        .map(d -> new DailyBar(d, 100, 101, 99, 100, 10_000_000)).toList(),
                bar -> MarketClock.nse().sessionOpenEpoch(bar.date()));
        // No longer trading: no bar on the latest session.
        stock("GONE", "NSE", sessionsTo(LATEST.minusDays(7), 40), 1_000, 1_000_000);
        // Half the days 200,000 shares at 100, half 0 shares at 1,900: the average volume times the average close is
        // 100,000 x 1,000 = 10 crore, though no day traded more than 2 crore.
        long uneven = instruments.findOrCreate("UNEVEN", "NSE", "CASH");
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < quarter.size(); i++) {
            double close = i % 2 == 0 ? 100 : 1_900;
            bars.add(new DailyBar(quarter.get(i), close, close, close, close, i % 2 == 0 ? 200_000 : 0));
        }
        dailyBars.upsert(uneven, bars, bar -> MarketClock.nse().sessionOpenEpoch(bar.date()));

        List<Map<String, Object>> rows = panel(5, 8, 25);

        assertEquals(List.of("BIG", "UNEVEN", "BSEONLY"), rows.stream().map(r -> r.get("Symbol")).toList());
        Map<String, Object> first = rows.getFirst();
        assertEquals(Arrays.asList("NSE", 10.0, 100_000.0, 1_000.0, 1_000.0, 60, 50_000.0, 60.0),
                Arrays.asList(first.get("Exchange"), first.get("Avg daily value, ₹ crore"), first.get("Avg daily volume"),
                        first.get("Avg close, ₹"), first.get("Latest close, ₹"), first.get("Sessions"),
                        first.get("Market cap, ₹ crore"), first.get("Free float %")));
        assertEquals(3, ((Number) panel(2, 8, 25).getFirst().get("Stocks")).intValue());
        assertEquals(List.of("BIG", "UNEVEN", "BSEONLY", "EIGHT"),
                panel(5, 5, 25).stream().map(r -> r.get("Symbol")).toList(), "a lower amount lets more in");
    }

    @Test
    @DisplayName("low float: what neither promoters nor the government hold, from the latest quarter's pattern, fewest first")
    void lowFloat() throws IOException {
        List<LocalDate> quarter = sessionsTo(LATEST, 60);
        long tight = stock("TIGHT", "NSE", quarter, 500, 10_000);
        marketCap(tight, 2_000);
        holding(tight, "2026-06", 60.0, null, 10, 10, 20);
        holding(tight, "2026-09", 85.0, null, 3, 2, 10);
        long psu = stock("PSU", "NSE", quarter, 100, 10_000);
        holding(psu, "2026-09", 0.0, 78.0, 5, 10, 7);
        long broad = stock("BROAD", "NSE", quarter, 100, 10_000);
        holding(broad, "2026-09", 50.0, 0.0, 20, 20, 10);
        long edge = stock("EDGE", "NSE", quarter, 100, 10_000);
        holding(edge, "2026-09", 75.0, 0.0, 10, 10, 5);
        long stale = stock("STALE", "NSE", quarter, 100, 10_000);
        holding(stale, "2026-06", 90.0, 0.0, 2, 2, 6);
        long unread = stock("UNREAD", "NSE", quarter, 100, 10_000);

        List<Map<String, Object>> rows = panel(6, 8, 25);

        assertEquals(List.of("TIGHT", "PSU", "EDGE"), rows.stream().map(r -> r.get("Symbol")).toList(),
                "the latest pattern counts; 25% is low; a pattern older than the latest quarter's is left out");
        Map<String, Object> first = rows.getFirst();
        assertEquals(Arrays.asList(15.0, 85.0, null, "Sep 2026", 2_000.0, 300.0, 0.5),
                Arrays.asList(first.get("Free float %"), first.get("Promoters %"), first.get("Government %"),
                        first.get("Quarter"), first.get("Market cap, ₹ crore"),
                        first.get("Free-float market cap, ₹ crore"), first.get("Avg daily value, ₹ crore")));
        assertEquals(22.0, rows.get(1).get("Free float %"), "the government's holding is not free either");
        assertEquals(3, ((Number) panel(3, 8, 25).getFirst().get("Stocks")).intValue());
        Map<String, Object> coverage = panel(4, 8, 25).getFirst();
        assertEquals(Arrays.asList(4, 6), Arrays.asList(coverage.get("With the latest quarter's pattern"),
                coverage.get("Stocks trading")), "STALE's pattern is old and UNREAD has none");
        assertEquals(unread, instruments.findOrCreate("UNREAD", "NSE", "CASH"));
    }

    @Test
    @DisplayName("both: the stocks on both lists, fewest free shares first, then the most traded")
    void liquidAndLowFloat() throws IOException {
        List<LocalDate> quarter = sessionsTo(LATEST, 60);
        // 100,000 shares at 1,000: 10 crore a day, with 15% free.
        long both = stock("BOTH", "NSE", quarter, 1_000, 100_000);
        holding(both, "2026-09", 85.0, null, 5, 5, 5);
        marketCap(both, 4_000);
        // As free, and twice as traded: first of the two.
        long busier = stock("BUSIER", "NSE", quarter, 2_000, 100_000);
        holding(busier, "2026-09", 85.0, null, 5, 5, 5);
        // Liquid, but widely held.
        long wide = stock("WIDE", "NSE", quarter, 1_000, 100_000);
        holding(wide, "2026-09", 40.0, 0.0, 30, 20, 10);
        // Low float, but 1 lakh a day.
        long thin = stock("THIN", "NSE", quarter, 100, 1_000);
        holding(thin, "2026-09", 90.0, 0.0, 2, 2, 6);
        // Low float and liquid, but its pattern is older than the latest quarter's.
        long stale = stock("STALE", "NSE", quarter, 1_000, 100_000);
        holding(stale, "2026-06", 90.0, 0.0, 2, 2, 6);
        // Low float and once liquid, but no longer trading.
        long gone = stock("GONE", "NSE", sessionsTo(LATEST.minusDays(7), 50), 1_000, 1_000_000);
        holding(gone, "2026-09", 90.0, 0.0, 2, 2, 6);

        List<Map<String, Object>> rows = panel(7, 8, 25);

        assertEquals(List.of("BUSIER", "BOTH"), rows.stream().map(r -> r.get("Symbol")).toList());
        Map<String, Object> second = rows.get(1);
        assertEquals(Arrays.asList(15.0, 10.0, 600.0, 4_000.0, 85.0, null, "Sep 2026", 1_000.0, 60),
                Arrays.asList(second.get("Free float %"), second.get("Avg daily value, \u20b9 crore"),
                        second.get("Free-float market cap, \u20b9 crore"), second.get("Market cap, \u20b9 crore"),
                        second.get("Promoters %"), second.get("Government %"), second.get("Quarter"),
                        second.get("Latest close, \u20b9"), second.get("Sessions")));
        for (int[] picked : new int[][]{{8, 25}, {5, 25}, {8, 40}, {20, 10}}) {
            java.util.Set<Object> liquid = new java.util.HashSet<>(panel(5, picked[0], picked[1]).stream()
                    .map(r -> r.get("Symbol")).toList());
            java.util.Set<Object> lowFloat = new java.util.HashSet<>(panel(6, picked[0], picked[1]).stream()
                    .map(r -> r.get("Symbol")).toList());
            liquid.retainAll(lowFloat);
            assertEquals(liquid, new java.util.HashSet<>(panel(7, picked[0], picked[1]).stream()
                    .map(r -> r.get("Symbol")).toList()), "exactly the stocks on both lists, at " + Arrays.toString(picked));
        }
    }

    @Test
    @DisplayName("the stocks on both lists are the first table on the page, above the two lists")
    void bothComesFirst() throws IOException {
        List<Integer> tables = StreamSupport.stream(dashboard("11-liquidity-float.json").path("panels").spliterator(), false)
                .filter(p -> p.path("type").asText().equals("table"))
                .sorted(java.util.Comparator.comparingInt(p -> p.path("gridPos").path("y").asInt()))
                .map(p -> p.path("id").asInt()).toList();
        assertEquals(List.of(7, 5, 6), tables);
    }

    @Test
    @DisplayName("Market Watch links here, and this page back to it")
    void links() throws IOException {
        assertTrue(StreamSupport.stream(dashboard("10-market-watch.json").path("links").spliterator(), false)
                .anyMatch(l -> l.path("url").asText().equals("/d/stockanalyzer-liquidity/liquidity-and-float")));
        assertTrue(StreamSupport.stream(dashboard("11-liquidity-float.json").path("links").spliterator(), false)
                .anyMatch(l -> l.path("url").asText().equals("/d/stockanalyzer-marketwatch/market-watch")));
        JsonNode variables = dashboard("11-liquidity-float.json").path("templating").path("list");
        assertEquals("8", variables.get(0).path("current").path("value").asText(), "over 8 crore a day, as asked");
    }
}
