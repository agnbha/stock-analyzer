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
 * The "every call" table on Candles and Calls, run as the dashboard runs it: each fill with the sentiment
 * and note recorded for it, and nothing borrowed from another symbol's fill made in the same second.
 */
class TradeCallsTableTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);
    private static final long SAME_SECOND = 1793766000L;

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

    private static Trade fill(String symbol, String id, int quantity) {
        return new Trade(0, symbol, id, "o-" + id, DAY, SAME_SECOND, Side.BUY, quantity, 100.0, Product.MIS, 0, null,
                Trade.ChargesSource.MODELLED, Trade.TradeSource.BROKER, null);
    }

    private record Row(String sentiment, String note, int quantity) {
    }

    private List<Row> rowsFor(String symbol) throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        JsonNode panel = StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == 12).findFirst().orElseThrow();
        String sql = panel.path("targets").get(0).path("queryText").asText()
                .replace("${session:sqlstring}", "'" + DAY + "'")
                .replace("$symbol", symbol);
        assertTrue(!sql.contains("$"), "an unfilled variable would make this prove nothing: " + sql);
        return database.read(connection -> {
            List<Row> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    rows.add(new Row(rs.getString("Sentiment"), rs.getString("Note"), rs.getInt("Qty")));
                }
            }
            return rows;
        });
    }

    @Test
    @DisplayName("each fill shows the sentiment and note recorded for it; an unnoted fill shows a dash")
    void showsTheNote() throws IOException {
        trades.importAll(List.of(fill("RELIANCE", "a", 10), fill("RELIANCE", "b", 20)), "NSE", "CASH");
        long first = trades.findRange(DAY, DAY).stream().filter(t -> t.quantity() == 10).findFirst().orElseThrow().id();
        notes.upsertAll(List.of(first), "BULLISH", "Results beat.", 1);

        List<Row> rows = rowsFor("RELIANCE");

        assertEquals(2, rows.size());
        assertTrue(rows.contains(new Row("BULLISH", "Results beat.", 10)), rows.toString());
        assertTrue(rows.contains(new Row("-", "", 20)), rows.toString());
    }

    @Test
    @DisplayName("another symbol's fill in the same second does not borrow, or lend, a note")
    void symbolsStayApart() throws IOException {
        trades.importAll(List.of(fill("RELIANCE", "a", 10), fill("TCS", "b", 10)), "NSE", "CASH");
        long reliance = trades.findRange(DAY, DAY).stream().filter(t -> t.symbol().equals("RELIANCE")).findFirst()
                .orElseThrow().id();
        notes.upsertAll(List.of(reliance), "BULLISH", "Only about RELIANCE.", 1);

        assertEquals(List.of(new Row("BULLISH", "Only about RELIANCE.", 10)), rowsFor("RELIANCE"));
        assertEquals(List.of(new Row("-", "", 10)), rowsFor("TCS"));
    }
}
