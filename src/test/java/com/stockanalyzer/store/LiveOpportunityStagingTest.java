package com.stockanalyzer.store;

import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.store.jdbc.SqliteGainOpportunityRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteLiveOpportunityRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The windows a session is showing must reflect the session as it stands, not
 * however much of it happened to be ingested. A top-3 computed over the first
 * 25 minutes is not a better answer than one computed over five hours, so
 * staged windows win until consolidation replaces them.
 */
class LiveOpportunityStagingTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 9, 2);
    private static final String DETECTOR = "topk-nonoverlap/highlow/v1";
    private static final long OPEN = 1788321300L;

    @TempDir
    Path directory;

    private Database database;
    private LiveOpportunityRepository live;
    private long instrumentId;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        live = new SqliteLiveOpportunityRepository(database);
        instrumentId = new SqliteInstrumentRepository(database).findOrCreate("TCS", "NSE", "CASH");

        // The stored version: ingested early, so it only saw the first 25 minutes.
        SqliteTradingDayRepository days = new SqliteTradingDayRepository(database);
        long tradingDayId = days.upsert(instrumentId, new DailyGainSummary("TCS", SESSION, 1,
                2300, 2320, 2295, 2310, 10_000, null, 26, OPEN, OPEN + 1500, List.of()), "groww");
        new SqliteGainOpportunityRepository(database).replace(tradingDayId, DETECTOR,
                List.of(window(1, 9 * 60, 22 * 60, 1.93)));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static GainOpportunity window(int rank, int entryOffset, int exitOffset, double gain) {
        return new GainOpportunity(rank, OPEN + entryOffset, OPEN + exitOffset,
                2300, 2300 * (1 + gain / 100), gain, (exitOffset - entryOffset) / 60);
    }

    private double queryDouble(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getDouble(1) : Double.NaN;
            }
        });
    }

    private String queryText(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    @Test
    @DisplayName("with nothing staged, the stored windows are what shows")
    void storedWindowsShowWhenNothingIsStaged() {
        assertEquals(1, (int) queryDouble("SELECT COUNT(*) FROM v_opportunity_markers"));
        assertEquals(1.93, queryDouble("SELECT gain_pct FROM v_opportunity_markers"), 0.001);
        assertEquals("final", queryText("SELECT source FROM v_opportunity_markers"));
    }

    @Test
    @DisplayName("staged windows replace the stale stored ones entirely")
    void stagedWindowsWin() {
        live.replace(instrumentId, SESSION, DETECTOR, List.of(
                window(1, 9 * 60, 45 * 60, 2.58),
                window(2, 127 * 60, 255 * 60, 0.54),
                window(3, 60, 120, 0.46)));

        assertEquals(3, (int) queryDouble("SELECT COUNT(*) FROM v_opportunity_markers"),
                "three staged windows, and the stale stored one is not mixed in");
        assertEquals("live", queryText("SELECT source FROM v_opportunity_markers WHERE rank = 1"));
        assertEquals(2.58, queryDouble(
                "SELECT gain_pct FROM v_opportunity_markers WHERE rank = 1"), 0.001);
        assertEquals(0.54, queryDouble(
                "SELECT gain_pct FROM v_opportunity_markers WHERE rank = 2"), 0.001,
                "a window that opened after the early ingest is now visible");
    }

    @Test
    @DisplayName("each tick replaces the previous staging rather than accumulating")
    void eachTickReplaces() {
        live.replace(instrumentId, SESSION, DETECTOR, List.of(window(1, 60, 120, 0.5)));
        live.replace(instrumentId, SESSION, DETECTOR,
                List.of(window(1, 9 * 60, 45 * 60, 2.58), window(2, 60, 120, 0.5)));

        assertEquals(2, live.countForSession(SESSION), "a later minute can change which win");
        assertEquals(2.58, queryDouble(
                "SELECT gain_pct FROM v_opportunity_markers WHERE rank = 1"), 0.001);
    }

    @Test
    @DisplayName("consolidation clears staging, handing the session back to the stored version")
    void consolidationHandsBack() {
        live.replace(instrumentId, SESSION, DETECTOR, List.of(window(1, 9 * 60, 45 * 60, 2.58)));

        assertEquals(1, live.deleteForSession(SESSION));
        assertEquals("final", queryText("SELECT source FROM v_opportunity_markers"),
                "the authoritative day is the record once it covers the whole session");
    }
}
