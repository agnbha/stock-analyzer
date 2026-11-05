package com.stockanalyzer.watchlist;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.WatchlistPickRepository;
import com.stockanalyzer.store.WatchlistPickRepository.Pick;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteWatchlistPickRepository;
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
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Watchlist's record of itself, checked against sessions seeded with known prices: complete
 * sessions only, one shortlist per evening, and outcomes read from what was stored afterwards.
 */
class WatchlistServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "05-watchlist.json");

    @TempDir
    Path directory;

    private Database database;
    private WatchlistPickRepository picks;
    private WatchlistService service;
    private long faster;
    private long riser;
    private long flat;
    private long faller;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        picks = new SqliteWatchlistPickRepository(database);
        service = new WatchlistService(picks);
        SqliteInstrumentRepository instruments = new SqliteInstrumentRepository(database);
        faster = instruments.findOrCreate("FASTER", "NSE", "CASH");
        riser = instruments.findOrCreate("RISER", "NSE", "CASH");
        flat = instruments.findOrCreate("FLAT", "NSE", "CASH");
        faller = instruments.findOrCreate("FALLER", "NSE", "CASH");

        // Thirty complete sessions: two climbing (one twice as fast), one level, one sliding.
        for (int i = 0; i < 30; i++) {
            LocalDate date = START.plusDays(i);
            session(faster, date, 100 + 2 * i, true);
            session(riser, date, 100 + i, true);
            session(flat, date, 100, true);
            session(faller, date, 200 - i, true);
        }
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("a session still in progress is never the one a symbol is ranked on")
    void aHalfDayRowDoesNotDecideTheRanking() {
        LocalDate last = START.plusDays(29);
        LocalDate next = last.plusDays(1);
        session(flat, next, 400, false);  // ingested at noon: its close is only the price at that moment

        assertEquals(last.toString(), query("SELECT session_date FROM v_bullish_ranking WHERE symbol = 'FLAT'"),
                "the ranking stays on the last full session");
        assertTrue(picks.ranking(next, 5).isEmpty(), "nobody has a complete session on the day in progress");
    }

    @Test
    @DisplayName("the shortlist is the highest scores of the evening, best first, ranked from one")
    void shortlistIsTheTopOfTheRanking() {
        LocalDate evening = START.plusDays(29);

        List<Pick> shortlist = service.logEvening(evening, 2);

        assertEquals(List.of("FASTER", "RISER"), shortlist.stream().map(Pick::symbol).toList());
        assertEquals(List.of(1, 2), shortlist.stream().map(Pick::rank).toList());
        assertEquals("FASTER", query("SELECT i.symbol FROM watchlist_pick p JOIN instrument i ON i.id = p.instrument_id "
                + "WHERE p.as_of_date = '" + evening + "' AND p.rank = 1"));
        assertEquals("live", query("SELECT DISTINCT source FROM watchlist_pick"));
    }

    @Test
    @DisplayName("running an evening again replaces its shortlist and leaves no rows of the old one")
    void rerunningReplaces() {
        LocalDate evening = START.plusDays(29);
        service.logEvening(evening, 3);
        service.logEvening(evening, 2);

        assertEquals("2", query("SELECT COUNT(*) FROM watchlist_pick WHERE as_of_date = '" + evening + "'"));
    }

    @Test
    @DisplayName("when nothing has a score yet, nothing is written and an earlier shortlist is kept")
    void nothingToRankLeavesTheRecordAlone() {
        LocalDate evening = START.plusDays(29);
        service.logEvening(evening, 2);

        assertTrue(service.logEvening(START.plusDays(3), 2).isEmpty(), "four sessions are too few for a 20-day average");
        assertTrue(service.logEvening(START.plusDays(200), 2).isEmpty(), "no session at all");
        assertEquals("2", query("SELECT COUNT(*) FROM watchlist_pick"));
    }

    @Test
    @DisplayName("what a pick did is read from the next stored session: gap, open to close, and the dip below the open")
    void outcomeIsReadFromTheNextSession() {
        LocalDate evening = START.plusDays(28);
        service.logEvening(evening, 1);  // FASTER, closing at 156; the next day it closes at 158 from an open of 157

        Map<String, Object> outcome = rows("SELECT * FROM v_watchlist_outcome").getFirst();

        assertEquals("FASTER", outcome.get("symbol"));
        assertEquals(START.plusDays(29).toString(), outcome.get("next_date"));
        assertEquals((157 - 156) / 156.0 * 100, (double) outcome.get("gap_pct"), 1e-9);
        assertEquals((158 - 157) / 157.0 * 100, (double) outcome.get("open_to_close_pct"), 1e-9);
        assertEquals((158 - 156) / 156.0 * 100, (double) outcome.get("close_to_close_pct"), 1e-9);
        assertEquals((156 - 157) / 157.0 * 100, (double) outcome.get("low_vs_open_pct"), 1e-9);
        assertEquals((159 - 157) / 157.0 * 100, (double) outcome.get("high_vs_open_pct"), 1e-9);
        assertEquals(4, ((Number) outcome.get("market_names")).intValue());

        // The comparison is the average over every symbol with a session that day, the pick included.
        double expected = 0;
        for (double close : new double[]{158, 129, 100, 171}) {  // FASTER, RISER, FLAT, FALLER on that day
            expected += 1 / (close - 1) * 100 / 4;                // each opened a rupee under its close
        }
        assertEquals(expected, (double) outcome.get("market_open_to_close_pct"), 1e-9);
    }

    @Test
    @DisplayName("a pick with no complete session after it has no outcome yet, and gets one when the session is stored whole")
    void aPartialFollowingSessionIsNotScoredUntilItIsWhole() {
        LocalDate evening = START.plusDays(29);
        service.logEvening(evening, 1);
        assertEquals("0", query("SELECT COUNT(*) FROM v_watchlist_outcome"), "nothing follows the last stored session");

        session(faster, evening.plusDays(1), 170, false);
        assertEquals("0", query("SELECT COUNT(*) FROM v_watchlist_outcome"), "a session in progress is not a result");

        session(faster, evening.plusDays(1), 170, true);  // consolidated after the close
        assertEquals("1", query("SELECT COUNT(*) FROM v_watchlist_outcome"), "the re-ingested session scores the pick");
    }

    @Test
    @DisplayName("a replay rebuilds the past evenings that have a score and keeps the ones logged live")
    void replayKeepsWhatWasLoggedLive() {
        LocalDate liveEvening = START.plusDays(29);
        service.logEvening(liveEvening, 2);

        int written = service.replay(START, liveEvening, 2);

        // Sessions 20 to 29 (indices 19 to 28) are the first with a 20-day average and a 5-day return.
        assertEquals(10, written);
        assertEquals("live", query("SELECT DISTINCT source FROM watchlist_pick WHERE as_of_date = '" + liveEvening + "'"));
        assertEquals("replay", query("SELECT DISTINCT source FROM watchlist_pick WHERE as_of_date < '" + liveEvening + "'"));
        assertEquals("22", query("SELECT COUNT(*) FROM watchlist_pick"), "ten replayed evenings and the live one, two picks each");
    }

    @Test
    @DisplayName("typical move from the open: how far above and below its open a stock went over its last twenty sessions")
    void typicalMoveFromTheOpen() {
        long wide = new SqliteInstrumentRepository(database).findOrCreate("WIDE", "NSE", "CASH");
        // Twenty-five sessions; the last twenty are what counts. Odd days reach +1.5% / -0.5%, even days +0.5% / -1.5%.
        for (int i = 0; i < 25; i++) {
            boolean odd = i % 2 == 1;
            session(wide, START.plusDays(i), 100, 100, odd ? 101.5 : 100.5, odd ? 99.5 : 98.5, true);
        }

        Map<String, Object> row = rows("SELECT * FROM v_open_excursion WHERE symbol = 'WIDE'").getFirst();

        assertEquals(20, ((Number) row.get("sessions")).intValue());
        // Of sessions 5..24: ten odd, ten even.
        assertEquals(50.0, (double) row.get("pct_sessions_up_1"), 1e-9);
        assertEquals(50.0, (double) row.get("pct_sessions_down_1"), 1e-9);
        assertEquals(1.0, (double) row.get("avg_high_vs_open_pct"), 1e-9);
        assertEquals(-1.0, (double) row.get("avg_low_vs_open_pct"), 1e-9);
    }

    @Test
    @DisplayName("the scorecard panel counts logged evenings and picks, separately for live and rebuilt shortlists")
    void scorecardPanel() throws IOException {
        service.replay(START, START.plusDays(29), 2);  // evenings 19..29 (by index); the last has no session after it
        service.logEvening(START.plusDays(27), 1);     // one evening logged live over its replayed version

        List<Map<String, Object>> card = rows(panelQuery(13));

        assertEquals(2, card.size());
        assertEquals("Logged each evening", card.get(0).get("Shortlists"));
        assertEquals(1, ((Number) card.get(0).get("Evenings")).intValue());
        assertEquals(1, ((Number) card.get(0).get("Picks scored")).intValue());
        assertEquals("Rebuilt from history", card.get(1).get("Shortlists"));
        assertEquals(9, ((Number) card.get(1).get("Evenings")).intValue(),
                "evenings 19..28 have a session after them; the live one (27) is counted in the other row");
        assertEquals(18, ((Number) card.get(1).get("Picks scored")).intValue());
        assertTrue(card.get(1).containsKey("Open→close vs avg (pts)"));
    }

    @Test
    @DisplayName("the shortlist panel carries each symbol's typical move from the open")
    void shortlistPanelShowsTypicalMoves() throws IOException {
        List<Map<String, Object>> shortlist = rows(panelQuery(10));

        assertEquals(4, shortlist.size(), "four symbols are seeded");
        Map<String, Object> top = shortlist.getFirst();
        assertEquals("FASTER", top.get("Symbol"));
        assertTrue(top.get("High vs open %") != null && top.get("Low vs open %") != null);
        assertTrue(top.get("Up 1%+ days %") != null && top.get("Down 1%+ days %") != null);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static String panelQuery(int id) throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        return StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == id).findFirst().orElseThrow()
                .path("targets").get(0).path("queryText").asText();
    }

    /** A session whose open is a rupee under its close; complete means its last candle is the day's last minute. */
    private void session(long instrument, LocalDate date, double close, boolean complete) {
        session(instrument, date, close - 1, close, close + 1, close - 2, complete);
    }

    private void session(long instrument, LocalDate date, double open, double close, double high, double low,
                         boolean complete) {
        long midnight = date.toEpochDay() * 86_400L;
        long first = midnight + 3 * 3_600L + 45 * 60L;                              // 09:15 IST
        long last = midnight + (complete ? 9 * 3_600L + 59 * 60L : 6 * 3_600L + 30 * 60L);  // 15:29 IST, or noon
        database.inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO trading_day (instrument_id, session_date, interval_minutes, open, high, low,
                                                        close, volume, candle_count, first_candle_ts, last_candle_ts,
                                                        source, ingested_at)
                    VALUES (?,?,1,?,?,?,?,1000,?,?,?,'test',0)""")) {
                upsert.setLong(1, instrument);
                upsert.setString(2, date.toString());
                upsert.setDouble(3, open);
                upsert.setDouble(4, high);
                upsert.setDouble(5, low);
                upsert.setDouble(6, close);
                upsert.setInt(7, complete ? 375 : 195);
                upsert.setLong(8, first);
                upsert.setLong(9, last);
                upsert.executeUpdate();
            }
        });
    }

    private String query(String sql) {
        List<Map<String, Object>> rows = rows(sql);
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().values().iterator().next());
    }

    private List<Map<String, Object>> rows(String sql) {
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
}
