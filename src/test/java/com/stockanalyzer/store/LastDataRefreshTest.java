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
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Last data refresh" tile on each dashboard, run over rows with known timestamps: it reports the newest of
 * the sources that page is drawn from, and does not borrow another page's source.
 */
class LastDataRefreshTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");

    @TempDir
    Path directory;

    private Database database;
    private long instrument;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instrument = new SqliteInstrumentRepository(database).findOrCreate("WIPRO", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private void execute(String sql, Object... parameters) {
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                statement.executeUpdate();
            }
        });
    }

    private void tradingDay(long ingestedAt) {
        execute("""
                INSERT INTO trading_day (instrument_id, session_date, interval_minutes, candle_count, source, ingested_at)
                VALUES (?, '2026-11-04', 1, 375, 'groww', ?)""", instrument, ingestedAt);
    }

    private void liveCandle(long updatedAt) {
        execute("""
                INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, updated_at)
                VALUES (?, 1, 1793763900, '2026-11-04', ?)""", instrument, updatedAt);
    }

    private long trade(long importedAt) {
        execute("""
                INSERT INTO trade (instrument_id, broker_trade_id, order_id, session_date, executed_ts, side, quantity, price,
                                   product, charges_source, source, imported_at)
                VALUES (?, 't1', 'o1', '2026-11-04', 1793763900, 'BUY', 10, 100, 'MIS', 'MODELLED', 'BROKER', ?)""",
                instrument, importedAt);
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT id FROM trade WHERE broker_trade_id = 't1'")) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private void reason(long tradeId, long recordedAt) {
        execute("INSERT INTO trade_reason (trade_id, reason_code, reason_source, recorded_at) VALUES (?, 'X', 'MANUAL', ?)",
                tradeId, recordedAt);
    }

    private void balance(long recordedAt) {
        execute("INSERT INTO account_balance (session_date, cash, source, recorded_at) VALUES ('2026-11-04', 1, 'broker', ?)",
                recordedAt);
    }

    /** The tile is found by its title, or by the name it shows when the title is left empty to save space. */
    private static JsonNode tile(JsonNode root, String name) {
        return StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("title").asText().equals(name)
                        || p.path("fieldConfig").path("defaults").path("displayName").asText().equals(name))
                .findFirst().orElse(null);
    }

    private Long refreshedAt(String dashboard) throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARDS.resolve(dashboard)));
        JsonNode tile = tile(root, "Last data refresh");
        assertTrue(tile != null, dashboard + " needs a 'Last data refresh' tile");
        String sql = tile.path("targets").get(0).path("queryText").asText();
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                rs.next();
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        });
    }

    @Test
    @DisplayName("with nothing recorded yet the tiles have nothing to show")
    void empty() throws IOException {
        for (String dashboard : new String[]{"01-overview.json", "02-candles-and-calls.json", "03-decision-reasons.json",
                "04-stock-trend.json", "05-watchlist.json", "07-company-details.json"}) {
            assertNull(refreshedAt(dashboard), dashboard);
        }
    }

    @Test
    @DisplayName("the live chart page reports the newest of the live candle, the last session ingested and the last fill")
    void candlesAndCalls() throws IOException {
        tradingDay(1_000);
        trade(2_000);
        assertEquals(2_000_000L, refreshedAt("02-candles-and-calls.json"), "the fill is newest");

        liveCandle(3_000);
        assertEquals(3_000_000L, refreshedAt("02-candles-and-calls.json"), "then the live candle, while the market is open");
    }

    @Test
    @DisplayName("the account page reports the newest of the fills, the balance and the reasons, and not a candle")
    void overview() throws IOException {
        liveCandle(9_000);
        long trade = trade(1_000);
        balance(2_000);
        reason(trade, 3_000);

        assertEquals(3_000_000L, refreshedAt("01-overview.json"));
    }

    @Test
    @DisplayName("Decision Reasons reports when the reasons were last worked out")
    void reasons() throws IOException {
        long trade = trade(5_000);
        reason(trade, 4_000);

        assertEquals(4_000_000L, refreshedAt("03-decision-reasons.json"));
    }

    @Test
    @DisplayName("Stock Trend and Watchlist report when a session was last written to the history")
    void dailyPages() throws IOException {
        liveCandle(9_000);
        trade(8_000);
        tradingDay(1_500);

        assertEquals(1_500_000L, refreshedAt("04-stock-trend.json"));
        assertEquals(1_500_000L, refreshedAt("05-watchlist.json"));
    }

    @Test
    @DisplayName("Company Details reports the newest of the last company page read and the latest price")
    void companyDetails() throws IOException {
        tradingDay(1_000);
        liveCandle(2_000);
        assertEquals(2_000_000L, refreshedAt("07-company-details.json"), "the price the ratios are restated at");

        execute("INSERT INTO company_profile (instrument_id, fetched_at) VALUES (?, 5000)", instrument);
        assertEquals(5_000_000L, refreshedAt("07-company-details.json"), "then the page read from screener.in");
    }

    @Test
    @DisplayName("every dashboard has the tile, as a time-since stat like CAN SLIM's Last scan")
    void everyDashboardHasTheTile() throws IOException {
        int checked = 0;
        try (var files = Files.list(DASHBOARDS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(file));
                String title = file.getFileName().toString().equals("06-canslim.json") ? "Last scan" : "Last data refresh";
                JsonNode tile = tile(root, title);
                assertTrue(tile != null, file.getFileName() + " needs a '" + title + "' tile");
                assertEquals("stat", tile.path("type").asText());
                assertEquals("dateTimeFromNow", tile.path("fieldConfig").path("defaults").path("unit").asText(),
                        file.getFileName() + ": shown as how long ago, like Last scan");
                checked++;
            }
        }
        assertEquals(7, checked);
    }

    @Test
    @DisplayName("the tile is a one-line label in the top right corner, so it takes no room from the panels")
    void isASmallLabelInTheTopRightCorner() throws IOException {
        for (String dashboard : new String[]{"01-overview.json", "02-candles-and-calls.json", "03-decision-reasons.json",
                "04-stock-trend.json", "05-watchlist.json", "07-company-details.json"}) {
            JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARDS.resolve(dashboard)));
            JsonNode tile = tile(root, "Last data refresh");
            JsonNode at = tile.path("gridPos");
            assertEquals(0, at.path("y").asInt(), dashboard + ": on the first row");
            assertEquals(1, at.path("h").asInt(), dashboard + ": one line high");
            assertEquals(24, at.path("x").asInt() + at.path("w").asInt(), dashboard + ": against the right edge");
            assertTrue(tile.path("transparent").asBoolean(), dashboard + ": no box of its own");

            int left = at.path("x").asInt();
            Set<Integer> bottoms = new TreeSet<>();
            for (JsonNode other : root.path("panels")) {
                if (other == tile) {
                    continue;
                }
                JsonNode o = other.path("gridPos");
                if (o.path("y").asInt() <= 1) {
                    bottoms.add(o.path("y").asInt() + o.path("h").asInt());
                }
                boolean sameRow = o.path("y").asInt() < at.path("y").asInt() + at.path("h").asInt()
                        && o.path("y").asInt() + o.path("h").asInt() > at.path("y").asInt();
                boolean sameColumns = o.path("x").asInt() < 24 && o.path("x").asInt() + o.path("w").asInt() > left;
                assertTrue(!(sameRow && sameColumns), dashboard + ": '" + other.path("title").asText()
                        + "' overlaps the label");
            }
            assertEquals(1, bottoms.size(), dashboard + ": the panels beside and under the label end on one line, "
                    + "so the label does not leave a gap or stagger the tiles; they end at rows " + bottoms);
        }
    }
}
