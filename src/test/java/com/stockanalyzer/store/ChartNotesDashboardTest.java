package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.model.ChartNote;
import com.stockanalyzer.store.jdbc.SqliteChartNoteRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chart notes as the dashboards read them, over seeded rows: the table on Candles and Calls lists the symbol's
 * notes and any with no symbol, newest first; the "Saved notes" layer draws only notes given a symbol by hand, since
 * a note tagged with its symbol is already drawn by Grafana itself.
 */
class ChartNotesDashboardTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");

    @TempDir
    Path directory;

    private Database database;
    private SqliteChartNoteRepository notes;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        notes = new SqliteChartNoteRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static ChartNote note(long id, long at, String symbol, String source, String sentiment, String text) {
        return new ChartNote(id, "stockanalyzer-candles", 20, symbol, source, at, null, sentiment,
                sentiment == null ? List.of() : List.of(sentiment), text, 9_000, null);
    }

    private static String sql(String dashboard, String panelOrLayer, boolean layer) throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARDS.resolve(dashboard)));
        JsonNode query = layer
                ? StreamSupport.stream(root.path("annotations").path("list").spliterator(), false)
                        .filter(a -> a.path("name").asText().equals(panelOrLayer)).findFirst().orElseThrow().path("target")
                : StreamSupport.stream(root.path("panels").spliterator(), false)
                        .filter(p -> p.path("id").asInt() == Integer.parseInt(panelOrLayer)).findFirst().orElseThrow()
                        .path("targets").get(0);
        String text = query.path("queryText").asText().replace("$symbol", "BHARTIARTL");
        assertTrue(!text.contains("$"), "an unfilled variable would make this prove nothing: " + text);
        return text;
    }

    private List<List<String>> run(String sql, String... columns) {
        return database.read(connection -> {
            List<List<String>> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (String column : columns) {
                        row.add(rs.getString(column));
                    }
                    rows.add(row);
                }
            }
            return rows;
        });
    }

    @Test
    @DisplayName("the table lists the symbol's notes and the ones with no symbol, newest first, and not another symbol's")
    void tableListsTheSymbolsNotesAndTheStrays() throws IOException {
        notes.upsert(List.of(
                note(1, 1_793_764_438L, null, null, "BULLISH", "Lower than last close. Should go up"),
                note(2, 1_793_765_000L, "BHARTIARTL", "TAG", "NEUTRAL", "Expecting less return"),
                note(3, 1_793_766_000L, "WIPRO", "TAG", "BEARISH", "About WIPRO")));

        List<List<String>> rows = run(sql("02-candles-and-calls.json", "22", false), "Symbol", "Sentiment", "Note");

        assertEquals(List.of(
                List.of("BHARTIARTL", "NEUTRAL", "Expecting less return"),
                List.of("(no symbol tag)", "BULLISH", "Lower than last close. Should go up")), rows);
    }

    @Test
    @DisplayName("a note deleted in Grafana is not listed")
    void deletedNotesAreHidden() throws IOException {
        notes.upsert(List.of(note(1, 1_793_764_438L, "BHARTIARTL", "TAG", "BULLISH", "gone")));
        notes.markMissing(java.util.Set.of(), 9_999);

        assertTrue(run(sql("02-candles-and-calls.json", "22", false), "Note").isEmpty());
    }

    @Test
    @DisplayName("the Saved notes layer draws a note given a symbol by hand, with the symbol, sentiment and words")
    void savedNotesLayer() throws IOException {
        notes.upsert(List.of(
                note(1, 1_793_764_438L, null, null, "BULLISH", "Lower than last close. Should go up"),
                note(2, 1_793_765_000L, "BHARTIARTL", "TAG", "NEUTRAL", "already drawn by Grafana")));
        notes.assignSymbol(1, "BHARTIARTL");

        List<List<String>> markers = run(sql("02-candles-and-calls.json", "Saved notes", true), "time", "text", "tags");

        assertEquals(List.of(List.of("1793764438", "BHARTIARTL - BULLISH: Lower than last close. Should go up", "BULLISH")),
                markers, "the tagged note is not drawn a second time");
    }

    @Test
    @DisplayName("on the account views the layer is not limited to a symbol")
    void accountViewLayer() throws IOException {
        notes.upsert(List.of(note(1, 1_793_764_438L, null, null, null, "one"), note(2, 1_793_765_000L, null, null, null, "two")));
        notes.assignSymbol(1, "BHARTIARTL");
        notes.assignSymbol(2, "WIPRO");

        List<List<String>> markers = run(sql("01-overview.json", "Saved notes", true), "text");

        assertEquals(List.of(List.of("BHARTIARTL: one"), List.of("WIPRO: two")), markers);
    }
}
