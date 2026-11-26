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
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CAN SLIM tile on Stock Trend, by running its own query: it says whether the stock is in the last scan (the stocks
 * CAN SLIM's own picker lists) and, if so, its letters and RS rating; and the tile's place in the top row and its link.
 * The readings are invented.
 */
class StockTrendCanSlimTileTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");
    private static final int TILE = 18;

    @TempDir
    Path directory;

    private Database database;
    private long scanned;
    private long droppedOut;
    private long never;
    private long newest;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        SqliteInstrumentRepository instruments = new SqliteInstrumentRepository(database);
        scanned = instruments.findOrCreate("SCANNED", "NSE", "CASH");
        droppedOut = instruments.findOrCreate("DROPPED", "NSE", "CASH");
        never = instruments.findOrCreate("NEVER", "NSE", "CASH");
        long older = run(1_800_000_000L);
        score(older, scanned, "CA-S---", 3, 40);
        score(older, droppedOut, "CANSLIM", 7, 95);
        newest = run(1_800_086_400L);
        score(newest, scanned, "C-NSLIM", 6, 83);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("a stock in the last scan shows its letters and RS rating from that scan")
    void aScannedStockShowsItsReading() throws Exception {
        List<Reading> rows = tile("SCANNED");

        assertEquals(1, rows.size());
        assertEquals("C-NSLIM", rows.getFirst().letters(), "the newest scan's reading, not the older one's");
        assertEquals(83, rows.getFirst().rs());
    }

    @Test
    @DisplayName("a stock the last scan did not read says so, even if an older scan read it")
    void aStockOutOfTheLastScanIsNotScanned() throws Exception {
        List<Reading> rows = tile("DROPPED");

        assertEquals(1, rows.size());
        assertEquals("Not scanned", rows.getFirst().letters(),
                "CAN SLIM's picker lists the last scan only, so an older reading is not there to open");
        assertNull(rows.getFirst().rs());
    }

    @Test
    @DisplayName("a stock never scanned, or one that is not known at all, says so too, in a single row")
    void aStockNeverScanned() throws Exception {
        for (String symbol : List.of("NEVER", "NOSUCHSYMBOL")) {
            List<Reading> rows = tile(symbol);

            assertEquals(1, rows.size(), symbol);
            assertEquals("Not scanned", rows.getFirst().letters(), symbol);
            assertNull(rows.getFirst().rs(), symbol);
        }
    }

    @Test
    @DisplayName("the tile is the same list CAN SLIM's own picker reads: a symbol is scanned exactly when it is in it")
    void sameListAsCanSlimsPicker() throws Exception {
        String picker = StreamSupport.stream(dashboard("06-canslim.json").path("templating").path("list").spliterator(), false)
                .filter(v -> v.path("name").asText().equals("symbol")).findFirst().orElseThrow().path("query").asText();
        assertTrue(picker.contains("FROM v_canslim_latest"), picker);
        assertTrue(sql().contains("FROM v_canslim_latest"), "the tile reads the same view");

        List<String> listed = database.read(connection -> {
            List<String> symbols = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT symbol FROM v_canslim_latest")) {
                while (rs.next()) {
                    symbols.add(rs.getString(1));
                }
            }
            return symbols;
        });
        for (String symbol : List.of("SCANNED", "DROPPED", "NEVER")) {
            assertEquals(listed.contains(symbol), !tile(symbol).getFirst().letters().equals("Not scanned"), symbol);
        }
    }

    @Test
    @DisplayName("the top row is five tiles side by side across the page, the new one beside Beta and Market breadth")
    void topRow() throws IOException {
        JsonNode dashboard = dashboard("04-stock-trend.json");
        List<int[]> row = new ArrayList<>();
        for (int id : new int[]{1, 2, 3, TILE, 4}) {
            JsonNode at = panel(dashboard, id).path("gridPos");
            row.add(new int[]{at.path("x").asInt(), at.path("w").asInt(), at.path("y").asInt() + at.path("h").asInt()});
        }
        int next = 0;
        for (int[] tile : row) {
            assertEquals(next, tile[0], "the tiles run left to right with no gap and no overlap");
            next = tile[0] + tile[1];
            assertEquals(5, tile[2], "every tile ends where the candle chart starts");
        }
        assertEquals(24, next, "across the whole page");
        assertEquals(6, panel(dashboard, TILE).path("gridPos").path("w").asInt(), "room for two values side by side");
    }

    @Test
    @DisplayName("the tile opens the stock on CAN SLIM, from the value and from the panel's link, carrying only the symbol")
    void linksToCanSlimOnTheSymbol() throws IOException {
        JsonNode canslim = dashboard("06-canslim.json");
        JsonNode tile = panel(dashboard("04-stock-trend.json"), TILE);
        List<JsonNode> links = new ArrayList<>();
        tile.path("links").forEach(links::add);
        tile.path("fieldConfig").path("defaults").path("links").forEach(links::add);

        assertEquals(2, links.size(), "a link on the panel and one on its values");
        for (JsonNode link : links) {
            String url = link.path("url").asText();
            assertEquals("/d/" + canslim.path("uid").asText() + "/can-slim?${symbol:queryparam}", url,
                    "the same address as the CAN SLIM button under the candles: the symbol, not the time range");
        }
    }

    @Test
    @DisplayName("the RS rating is coloured as CAN SLIM's own table colours it")
    void rsColoursAsOnCanSlim() throws IOException {
        JsonNode there = null;
        for (JsonNode p : dashboard("06-canslim.json").path("panels")) {
            for (JsonNode override : p.path("fieldConfig").path("overrides")) {
                if (p.path("id").asInt() == 21 && override.path("matcher").path("options").asText().equals("RS")) {
                    there = property(override, "thresholds");
                }
            }
        }
        JsonNode here = null;
        for (JsonNode override : panel(dashboard("04-stock-trend.json"), TILE).path("fieldConfig").path("overrides")) {
            if (override.path("matcher").path("options").asText().equals("RS rating")) {
                here = property(override, "thresholds");
            }
        }

        assertTrue(there != null && here != null);
        assertEquals(there, here, "red below 70, orange to 80, green from 80, the bar CAN SLIM uses");
    }

    private static JsonNode property(JsonNode override, String id) {
        for (JsonNode p : override.path("properties")) {
            if (p.path("id").asText().equals(id)) {
                return p.path("value");
            }
        }
        return null;
    }

    private record Reading(String letters, Integer rs) {
    }

    private String sql() throws IOException {
        return panel(dashboard("04-stock-trend.json"), TILE).path("targets").get(0).path("rawQueryText").asText();
    }

    /** The tile's own query for the symbol: one row of its two values. */
    private List<Reading> tile(String symbol) throws Exception {
        String sql = sql().replace("$symbol", symbol);
        assertTrue(!sql.contains("$"), sql);
        return database.read(connection -> {
            List<Reading> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                assertEquals(List.of("Letters", "RS rating"),
                        List.of(rs.getMetaData().getColumnLabel(1), rs.getMetaData().getColumnLabel(2)));
                while (rs.next()) {
                    int value = rs.getInt(2);
                    boolean blank = rs.wasNull();
                    rows.add(new Reading(rs.getString(1), blank ? null : value));
                }
            }
            return rows;
        });
    }

    private static JsonNode dashboard(String file) throws IOException {
        return JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARDS.resolve(file)));
    }

    private static JsonNode panel(JsonNode dashboard, int id) {
        return StreamSupport.stream(dashboard.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == id).findFirst().orElseThrow();
    }

    private long run(long at) {
        long[] id = new long[1];
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO canslim_run (run_at, screened, evaluated, market_symbol, market_state)
                    VALUES (?, 0, 0, 'NIFTYBEES', 'CONFIRMED_UPTREND')""", Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, at);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    id[0] = keys.getLong(1);
                }
            }
        });
        return id[0];
    }

    private void score(long run, long instrument, String letters, int passed, int rsRating) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO canslim_score (run_id, instrument_id, source, letters, letters_passed, rs_rating, signal)
                    VALUES (?, ?, 'MARKET', ?, ?, ?, 'NONE')""")) {
                insert.setLong(1, run);
                insert.setLong(2, instrument);
                insert.setString(3, letters);
                insert.setInt(4, passed);
                insert.setInt(5, rsRating);
                insert.executeUpdate();
            }
        });
    }
}
