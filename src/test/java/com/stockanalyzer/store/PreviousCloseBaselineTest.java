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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Previous close" line on the minute-candle panel of Candles and Calls, checked by running the
 * panel's own query over seeded sessions rather than by reading the SQL.
 */
class PreviousCloseBaselineTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");
    private static final String D0 = "2026-08-25";
    private static final String D1 = "2026-08-26";
    private static final String D2 = "2026-08-27";
    private static final String D3 = "2026-08-28";

    @TempDir
    Path directory;

    private Database database;
    private long test;
    private long other;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        SqliteInstrumentRepository instruments = new SqliteInstrumentRepository(database);
        test = instruments.findOrCreate("TEST", "NSE", "CASH");
        other = instruments.findOrCreate("OTHER", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("each selected session carries the close of the session before it")
    void eachSessionHasItsPreviousClose() throws Exception {
        session(test, D1, 100.0);
        session(test, D2, 110.0);
        candles(test, D2);
        candles(test, D3);

        Map<String, Double> baseline = baselineBySession(D2, D3);

        assertEquals(100.0, baseline.get(D2), "D2 is measured from D1's close");
        assertEquals(110.0, baseline.get(D3), "D3 from D2's, so the line steps with the sessions");
    }

    @Test
    @DisplayName("a hole in this symbol's own history gives no line, not a close from days earlier")
    void aMissingSessionIsNotPatchedWithAnOlderOne() throws Exception {
        session(test, D0, 90.0);
        session(other, D1, 50.0);
        candles(test, D2);

        assertNull(baselineBySession(D2).get(D2),
                "D1 was a trading day (another symbol has it); this symbol's D0 close must not stand in");
    }

    @Test
    @DisplayName("the earliest stored session has nothing to be measured from")
    void firstSessionHasNoBaseline() throws Exception {
        session(test, D1, 100.0);
        candles(test, D1);

        assertNull(baselineBySession(D1).get(D1));
    }

    @Test
    @DisplayName("the panel draws it as a dotted line that steps between sessions")
    void drawnAsADottedStepLine() throws Exception {
        JsonNode override = StreamSupport.stream(panel().path("fieldConfig").path("overrides").spliterator(), false)
                .filter(o -> "Previous close".equals(o.path("matcher").path("options").asText()))
                .findFirst().orElseThrow();
        Map<String, JsonNode> properties = new LinkedHashMap<>();
        override.path("properties").forEach(p -> properties.put(p.path("id").asText(), p.path("value")));

        assertEquals("line", properties.get("custom.drawStyle").asText());
        assertEquals("dot", properties.get("custom.lineStyle").path("fill").asText());
        assertEquals("stepAfter", properties.get("custom.lineInterpolation").asText());
        assertTrue(panel().path("description").asText().contains("Previous close"));
    }

    private static JsonNode panel() throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        return StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == 20).findFirst().orElseThrow();
    }

    /** The panel's own query, with Grafana's variables filled in, run over the seeded sessions. */
    private Map<String, Double> baselineBySession(String... sessions) throws Exception {
        String list = String.join("','", sessions);
        String sql = panel().path("targets").get(0).path("queryText").asText()
                .replace("${session:sqlstring}", "'" + list + "'")
                .replace("$symbol", "TEST")
                .replace("$detector", "topk-nonoverlap/highlow/v1");
        assertTrue(!sql.contains("$"), "an unfilled variable would make this prove nothing: " + sql);
        return database.read(connection -> {
            Map<String, Double> bySession = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    double value = rs.getDouble("Previous close");
                    Double baseline = rs.wasNull() ? null : value;
                    String session = sessionOf(rs.getLong("time"));
                    if (bySession.containsKey(session)) {
                        assertEquals(bySession.get(session), baseline, "one level for the whole session");
                    }
                    bySession.put(session, baseline);
                }
            }
            return bySession;
        });
    }

    private static String sessionOf(long epoch) {
        return java.time.LocalDate.ofEpochDay(epoch / 86_400L).toString();
    }

    private void session(long instrument, String date, double close) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO trading_day (instrument_id, session_date, interval_minutes, close, candle_count,
                                             source, ingested_at)
                    VALUES (?,?,1,?,375,'groww',0)""")) {
                insert.setLong(1, instrument);
                insert.setString(2, date);
                insert.setDouble(3, close);
                insert.executeUpdate();
            }
        });
    }

    /** Three one-minute candles in the session, stamped within its own UTC day so the test can tell sessions apart. */
    private void candles(long instrument, String date) {
        long base = java.time.LocalDate.parse(date).toEpochDay() * 86_400L + 4 * 3_600L;
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, open, high,
                                             low, close, volume, provisional, updated_at)
                    VALUES (?,1,?,?,100,101,99,100,1000,0,0)""")) {
                for (int minute = 0; minute < 3; minute++) {
                    insert.setLong(1, instrument);
                    insert.setLong(2, base + minute * 60L);
                    insert.setString(3, date);
                    insert.executeUpdate();
                }
            }
        });
    }
}
