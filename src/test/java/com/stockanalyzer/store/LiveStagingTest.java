package com.stockanalyzer.store;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.store.jdbc.SqliteCandleRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteLiveCandleRepository;
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
 * Staging exists so the dashboards can follow a session in progress. The rule
 * it must never break: staged data is visible, but the canonical tape always
 * wins once it exists.
 */
class LiveStagingTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 9, 1);
    private static final long OPEN = 1788234300L;

    @TempDir
    Path directory;

    private Database database;
    private LiveCandleRepository live;
    private CandleRepository canonical;
    private long instrumentId;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        live = new SqliteLiveCandleRepository(database);
        canonical = new SqliteCandleRepository(database);
        instrumentId = new SqliteInstrumentRepository(database)
                .findOrCreate("TCS", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static Candle candle(int minute, double close) {
        return new Candle(OPEN + minute * 60L, close, close + 1, close - 1, close, 100);
    }

    private String queryText(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private int queryInt(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    @Test
    @DisplayName("a tick stages what it has seen, flagging the candle still forming")
    void stagesTheSessionSoFar() {
        live.upsertAll(instrumentId, SESSION, 1,
                List.of(candle(0, 100), candle(1, 101), candle(2, 102)), OPEN + 120);

        assertEquals(3, live.countForSession(SESSION));
        assertEquals(3, queryInt("SELECT COUNT(*) FROM v_intraday_merged WHERE symbol='TCS'"));
        assertEquals("forming", queryText("SELECT source FROM v_intraday_merged "
                + "ORDER BY ts_epoch DESC LIMIT 1"), "the newest candle is not settled yet");
        assertEquals("live", queryText("SELECT source FROM v_intraday_merged "
                + "ORDER BY ts_epoch LIMIT 1"));
    }

    @Test
    @DisplayName("a later tick settles the candle that was forming")
    void laterTicksSettleTheForwardEdge() {
        live.upsertAll(instrumentId, SESSION, 1, List.of(candle(0, 100), candle(1, 101)), OPEN + 60);
        live.upsertAll(instrumentId, SESSION, 1,
                List.of(candle(0, 100), candle(1, 107), candle(2, 108)), OPEN + 120);

        assertEquals(3, live.countForSession(SESSION), "the same minute is not duplicated");
        assertEquals(107.0, Double.parseDouble(queryText(
                "SELECT close FROM v_intraday_merged WHERE ts_epoch = " + (OPEN + 60))), 0.001,
                "the settled value replaced the provisional one");
        assertEquals("live", queryText("SELECT source FROM v_intraday_merged WHERE ts_epoch = "
                + (OPEN + 60)));
    }

    @Test
    @DisplayName("the canonical tape wins the moment it exists, with no duplicate minutes")
    void consolidatedDataSupersedesStaging() {
        live.upsertAll(instrumentId, SESSION, 1,
                List.of(candle(0, 100), candle(1, 101), candle(2, 102)), 0);

        // What consolidation does: write the authoritative day, then clear staging.
        canonical.saveAll(instrumentId, 1, List.of(candle(0, 100.5), candle(1, 101.5)));

        assertEquals(3, queryInt("SELECT COUNT(*) FROM v_intraday_merged WHERE symbol='TCS'"),
                "two consolidated minutes plus the one still only staged");
        assertEquals("final", queryText("SELECT source FROM v_intraday_merged "
                + "WHERE ts_epoch = " + OPEN), "canonical wins for a minute present in both");
        assertEquals(100.5, Double.parseDouble(queryText(
                "SELECT close FROM v_intraday_merged WHERE ts_epoch = " + OPEN)), 0.001);

        assertEquals(2, live.deleteConsolidated(), "only the superseded rows are dropped");
        assertEquals(1, live.countForSession(SESSION));
        assertEquals(3, queryInt("SELECT COUNT(*) FROM v_intraday_merged WHERE symbol='TCS'"),
                "clearing staging changes nothing the panel can see");
    }

    @Test
    @DisplayName("staged rows never reach the canonical candle table on their own")
    void stagingIsNotCanonical() {
        live.upsertAll(instrumentId, SESSION, 1, List.of(candle(0, 100)), OPEN);

        assertEquals(0, queryInt("SELECT COUNT(*) FROM candle"));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM v_intraday_candles"));
    }

    @Test
    @DisplayName("VWAP is the running volume-weighted average within the session")
    void vwapIsVolumeWeightedAndCumulative() {
        // typical price = (high + low + close) / 3, and the helper builds
        // high = close + 1, low = close - 1, so typical == close.
        live.upsertAll(instrumentId, SESSION, 1,
                List.of(weighted(0, 100, 10), weighted(1, 200, 30)), 0);

        // First minute: 100 at weight 10 -> 100.
        assertEquals(100.0, Double.parseDouble(queryText(
                "SELECT vwap FROM v_intraday_merged WHERE ts_epoch = " + OPEN)), 0.001);
        // Second: (100*10 + 200*30) / 40 = 175, not the 150 a plain mean gives.
        assertEquals(175.0, Double.parseDouble(queryText(
                "SELECT vwap FROM v_intraday_merged WHERE ts_epoch = " + (OPEN + 60))), 0.001);
    }

    @Test
    @DisplayName("VWAP restarts each session rather than carrying yesterday's volume in")
    void vwapResetsEachSession() {
        LocalDate previous = SESSION.minusDays(1);
        long previousOpen = OPEN - 86400;

        live.upsertAll(instrumentId, previous, 1,
                List.of(new Candle(previousOpen, 500, 501, 499, 500, 1_000_000)), 0);
        live.upsertAll(instrumentId, SESSION, 1, List.of(weighted(0, 100, 10)), 0);

        assertEquals(100.0, Double.parseDouble(queryText(
                "SELECT vwap FROM v_intraday_merged WHERE ts_epoch = " + OPEN)), 0.001,
                "a million shares traded at 500 yesterday must not move today's first minute");
    }

    private static Candle weighted(int minute, double close, long volume) {
        return new Candle(OPEN + minute * 60L, close, close + 1, close - 1, close, volume);
    }
}
