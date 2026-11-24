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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stock Trend's liquidity screen and chart, run over invented bars, a company profile and journal fills: the slide's
 * filters, and turnover against 10 and 20 times the largest position.
 */
class StockTrendLiquidityTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");

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

    private static List<LocalDate> sessions(int count) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = LocalDate.of(2026, 9, 1); days.size() < count; day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(day);
            }
        }
        return days;
    }

    private long stock(String symbol, int sessions, double close, long volume) {
        long id = instruments.findOrCreate(symbol, "NSE", "CASH");
        dailyBars.upsert(id, sessions(sessions).stream()
                        .map(d -> new DailyBar(d, close, close + 1, close - 1, close, volume)).toList(),
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

    private int fills;

    private void fill(long instrument, String profile, String side, LocalDate day, String order, int quantity, double price) {
        fills++;
        execute("""
                INSERT INTO trade (instrument_id, broker_trade_id, order_id, session_date, executed_ts, side, quantity, price,
                                   product, charges_source, source, imported_at, profile)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CNC', 'MODELLED', 'BROKER', 1, ?)""",
                instrument, "t" + fills, order, day.toString(), 1_000L + fills, side, quantity, price, profile);
    }

    private List<Map<String, Object>> panel(int id, String symbol, String box) throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARDS.resolve("04-stock-trend.json")));
        JsonNode found = StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == id).findFirst().orElseThrow();
        String sql = found.path("targets").get(0).path("rawQueryText").asText()
                .replace("$symbol", symbol).replace("$max_position", box);
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

    private static List<List<Object>> screen(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> List.of(r.get("Screen"), r.get("Value"), r.get("Result"))).toList();
    }

    @Test
    @DisplayName("the slide's filters: a close above 25 and at most 10,000, a market cap of 300 crore, 5 crore traded")
    void theScreen() throws IOException {
        // 50,000 shares at 1,000: 5 crore on the day, exactly the line.
        long id = stock("LIQUID", 25, 1_000, 50_000);
        execute("INSERT INTO company_profile (instrument_id, fetched_at, market_cap_cr) VALUES (?, 1, 300)", id);
        // A close of exactly 25 is not above it.
        stock("CHEAP", 25, 25, 10_000);

        assertEquals(List.of(
                List.of("Close above ₹25", "₹1,000.00", "Passes"),
                List.of("Close at most ₹10,000", "₹1,000.00", "Passes"),
                List.of("Market cap at least ₹300 crore", "₹300 crore", "Passes"),
                List.of("Value traded on the latest session at least ₹5 crore", "₹5.00 crore", "Passes"),
                List.of("Turnover (20-day average volume × average close) 10 to 20× your largest position",
                        "₹5.00 crore", "Unknown")),
                screen(panel(16, "LIQUID", "")), "no box and no buys: the position is not known");
        List<List<Object>> cheap = screen(panel(16, "CHEAP", ""));
        assertEquals("Fails", cheap.get(0).get(2));
        assertEquals(List.of("Market cap at least ₹300 crore", "not read yet", "Unknown"), cheap.get(2));
        assertEquals("Fails", cheap.get(3).get(2));
    }

    @Test
    @DisplayName("the largest position: the box, or the largest buy order of the last 90 days with its fills together")
    void largestPosition() throws IOException {
        // 20 sessions of 100,000 shares at 100: 1 crore of turnover.
        long id = stock("TEN", 25, 100, 100_000);
        LocalDate recent = LocalDate.now().minusDays(10);
        fill(id, "main", "BUY", recent, "o1", 300, 1_000);
        fill(id, "main", "BUY", recent, "o1", 200, 1_000);
        fill(id, "second", "BUY", recent, "o2", 400, 1_000);
        fill(id, "main", "SELL", recent, "o3", 5_000, 1_000);
        fill(id, "main", "BUY", LocalDate.now().minusDays(120), "o4", 9_000, 1_000);

        List<List<Object>> byJournal = screen(panel(16, "TEN", ""));
        assertEquals(List.of("Turnover (20-day average volume × average close) 10 to 20× your largest position",
                        "₹1.00 crore, 20.0× ₹500,000", "Passes, 20×"), byJournal.get(4),
                "order o1's two fills make 5 lakh; a sell, and a buy 120 days ago, do not count");

        assertEquals(List.of("Turnover (20-day average volume × average close) 10 to 20× your largest position",
                        "₹1.00 crore, 12.5× ₹800,000", "Passes, 10×"),
                screen(panel(16, "TEN", "800000")).get(4), "the box wins");
        assertEquals("Fails", screen(panel(16, "TEN", "2000000")).get(4).get(2), "turnover only five times it");
    }

    @Test
    @DisplayName("the chart: value traded a session, the 20-day turnover once there are 20, the 5 crore line and the multiples")
    void theChart() throws IOException {
        long id = instruments.findOrCreate("RAMP", "NSE", "CASH");
        List<LocalDate> days = sessions(21);
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            bars.add(new DailyBar(days.get(i), 100, 101, 99, i % 2 == 0 ? 100 : 300, i % 2 == 0 ? 300_000 : 100_000));
        }
        dailyBars.upsert(id, bars, bar -> MarketClock.nse().sessionOpenEpoch(bar.date()));

        List<Map<String, Object>> rows = panel(17, "RAMP", "1000000");

        assertEquals(21, rows.size());
        assertEquals(3.0, rows.getFirst().get("Value traded, ₹ crore"), "300,000 x 100");
        assertNull(rows.get(18).get("Turnover: 20-day average volume × average close"), "19 sessions are not 20");
        // The last 20 sessions: ten of 300,000 at 100 and ten of 100,000 at 300 (from the second): averages 200,000
        // shares and 200, so 4 crore, though no day traded more than 3.
        assertEquals(4.0, rows.getLast().get("Turnover: 20-day average volume × average close"));
        assertEquals(Arrays.asList(5, 1.0, 2.0), Arrays.asList(rows.getLast().get("Screen: ₹5 crore"),
                rows.getLast().get("10× your largest position"), rows.getLast().get("20× your largest position")));
    }
}
