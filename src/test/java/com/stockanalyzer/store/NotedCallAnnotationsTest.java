package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.jdbc.SqliteTradeNoteRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeRepository;
import com.stockanalyzer.util.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Noted buys" and "Noted sells" annotation queries, run as the dashboards run them over seeded fills:
 * one marker per noted order, saying what the call was and what you wrote, and nothing for fills with no note.
 */
class NotedCallAnnotationsTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);
    private static final long T1 = 1793764000L;

    @TempDir
    Path directory;

    private Database database;
    private SqliteTradeRepository trades;
    private SqliteTradeNoteRepository notes;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        trades = new SqliteTradeRepository(database);
        notes = new SqliteTradeNoteRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private record Marker(long time, String text, String tags) {
    }

    private void fill(String symbol, String orderId, Side side, long at, int quantity, double price, String sentiment,
                      String note) {
        trades.importAll(List.of(new Trade(0, symbol, orderId + "-" + at + "-" + quantity, orderId, DAY, at, side,
                quantity, price, Product.MIS, 0, null, Trade.ChargesSource.MODELLED, Trade.TradeSource.BROKER, null)),
                "NSE", "CASH");
        if (sentiment != null || note != null) {
            long id = trades.findRange(DAY, DAY).stream().filter(t -> t.brokerTradeId().equals(orderId + "-" + at + "-"
                    + quantity)).findFirst().orElseThrow().id();
            notes.upsertAll(List.of(id), sentiment, note, 1);
        }
    }

    private List<Marker> markers(String dashboard, String annotation, String symbol) throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARDS.resolve(dashboard)));
        JsonNode query = StreamSupport.stream(root.path("annotations").path("list").spliterator(), false)
                .filter(a -> a.path("name").asText().equals(annotation)).findFirst().orElseThrow();
        String sql = query.path("target").path("queryText").asText().replace("$symbol", symbol == null ? "" : symbol);
        assertTrue(!sql.contains("$"), "an unfilled variable would make this prove nothing: " + sql);
        return database.read(connection -> {
            List<Marker> found = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    found.add(new Marker(rs.getLong("time"), rs.getString("text"), rs.getString("tags")));
                }
            }
            return found;
        });
    }

    @Test
    @DisplayName("one marker per noted order, at its first fill, with the quantity, average price, sentiment and note")
    void oneMarkerPerOrder() throws IOException {
        // One order, two fills at different prices in the same second: 300 @ 100 and 200 @ 101 average 100.40.
        fill("AAA", "buy-1", Side.BUY, T1, 300, 100.00, "BULLISH", "Breakout on volume.");
        fill("AAA", "buy-1", Side.BUY, T1, 200, 101.00, "BULLISH", "Breakout on volume.");

        List<Marker> buys = markers("02-candles-and-calls.json", "Noted buys", "AAA");

        assertEquals(List.of(new Marker(T1, "AAA BUY 500 @ 100.40 - BULLISH: Breakout on volume.", "BUY,BULLISH")), buys);
    }

    @Test
    @DisplayName("buys and sells are drawn apart, and a sentiment or a note alone is enough")
    void sidesApartAndPartialNotes() throws IOException {
        fill("AAA", "buy-1", Side.BUY, T1, 500, 100.00, null, "Just the words.");
        fill("AAA", "sell-1", Side.SELL, T1 + 3600, 500, 102.00, "CALM", null);

        assertEquals(List.of(new Marker(T1, "AAA BUY 500 @ 100.00: Just the words.", "BUY")),
                markers("02-candles-and-calls.json", "Noted buys", "AAA"));
        assertEquals(List.of(new Marker(T1 + 3600, "AAA SELL 500 @ 102.00 - CALM", "SELL,CALM")),
                markers("02-candles-and-calls.json", "Noted sells", "AAA"));
    }

    @Test
    @DisplayName("a fill with no note is not drawn, and another symbol's call stays off a symbol's chart")
    void unnotedAndOtherSymbols() throws IOException {
        fill("AAA", "buy-1", Side.BUY, T1, 500, 100.00, "BULLISH", "mine");
        fill("AAA", "buy-2", Side.BUY, T1 + 600, 100, 101.00, null, null);
        fill("BBB", "buy-3", Side.BUY, T1 + 900, 50, 200.00, "NERVOUS", "theirs");

        List<Marker> aaa = markers("04-stock-trend.json", "Noted buys", "AAA");

        assertEquals(1, aaa.size());
        assertTrue(aaa.getFirst().text().startsWith("AAA BUY 500"), aaa.toString());
    }

    @Test
    @DisplayName("on the account views, where there is no symbol picker, every symbol's noted calls are drawn")
    void accountViewsShowEverySymbol() throws IOException {
        fill("AAA", "buy-1", Side.BUY, T1, 500, 100.00, "BULLISH", "one");
        fill("BBB", "buy-3", Side.BUY, T1 + 900, 50, 200.00, "NERVOUS", "two");

        List<Marker> all = markers("01-overview.json", "Noted buys", null);

        assertEquals(List.of("AAA BUY 500 @ 100.00 - BULLISH: one", "BBB BUY 50 @ 200.00 - NERVOUS: two"),
                all.stream().map(Marker::text).toList());
    }
}
