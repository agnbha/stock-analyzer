package com.stockanalyzer.canslim;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.store.CompanyProfileRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteCompanyProfileRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.util.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeping the company profiles current, and showing them: which companies are read and in what order, what
 * is stored, how the price-dependent ratios are restated at today's price, and what the dashboard panels
 * return for it.
 */
class CompanyProfileServiceTest {

    private static final long NOW = 1_793_000_000L;
    private static final long WEEK = 7 * 86_400L;
    private static final long QUARTER_YEAR = 90 * 86_400L;
    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");

    @TempDir
    Path directory;

    private Database database;
    private CompanyProfileRepository profiles;
    private SqliteInstrumentRepository instruments;
    private CompanyProfile welspun;
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        database = TestDatabase.open(directory);
        profiles = new SqliteCompanyProfileRepository(database);
        instruments = new SqliteInstrumentRepository(database);
        try (InputStream in = CompanyProfileServiceTest.class.getResourceAsStream("/screener/company-welspunliv.html")) {
            welspun = ScreenerCompanyParser.profile(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("never-read companies come first, then the oldest, and only as many as the page budget allows")
    void readsTheStalestFirstWithinTheBudget() {
        long a = id("AAA");
        long b = id("BBB");
        long c = id("CCC");
        id("NEW");
        profiles.save(a, welspun, NOW - 20 * 86_400L);   // oldest of the stale
        profiles.save(b, welspun, NOW - 10 * 86_400L);
        profiles.save(c, welspun, NOW - 1 * 86_400L);    // fresh: not due

        CompanyProfileService.Result result = service(symbol -> Optional.of(welspun))
                .refresh(List.of("AAA", "BBB", "CCC", "NEW"), 2, WEEK, false);

        assertEquals(List.of("NEW", "AAA"), asked, "never read first, then the oldest; BBB is over budget");
        assertEquals(2, result.fetched());
        assertEquals(1, result.notAttempted(), "BBB was due but over the budget");
        assertNull(result.stoppedBy());
    }

    @Test
    @DisplayName("a company whose copy is fresh is left alone unless it is forced")
    void freshCopiesAreNotReadAgain() {
        profiles.save(id("AAA"), welspun, NOW - 86_400L);

        assertEquals(0, service(symbol -> Optional.of(welspun)).refresh(List.of("AAA"), 0, WEEK, false).fetched());
        assertTrue(asked.isEmpty());
        assertEquals(1, service(symbol -> Optional.of(welspun)).refresh(List.of("AAA"), 0, WEEK, true).fetched());
        assertEquals(List.of("AAA"), asked);
    }

    @Test
    @DisplayName("a page with nothing on the company is remembered, so it is not asked about again every night")
    void aCompanyWithNoProfileIsRecorded() {
        long etf = id("ETFX");

        CompanyProfileService.Result result = service(symbol -> Optional.empty())
                .refresh(List.of("ETFX"), 0, WEEK, false);

        assertEquals(1, result.withoutProfile());
        assertEquals(Optional.of(NOW), profiles.fetchedAt(etf));
        asked.clear();
        service(symbol -> Optional.empty()).refresh(List.of("ETFX"), 0, WEEK, false);
        assertTrue(asked.isEmpty(), "read once, so not due for a week");
        assertEquals("0", scalar("SELECT COUNT(*) FROM company_quarter"));
    }

    @Test
    @DisplayName("a rate limit stops the run, keeps what was read, and counts the rest as still due")
    void aRateLimitStopsTheRun() {
        id("AAA");
        id("BBB");
        id("CCC");
        CompanyFundamentalsClient limited = new CompanyFundamentalsClient() {
            @Override
            public Optional<CompanyFundamentals> fetch(String symbol) {
                return Optional.empty();
            }

            @Override
            public Optional<CompanyProfile> fetchProfile(String symbol) {
                asked.add(symbol);
                if (symbol.equals("BBB")) {
                    throw new ScreenerException("screener.in is still rate limiting (HTTP 429)", false);
                }
                return Optional.of(welspun);
            }
        };

        CompanyProfileService.Result result = new CompanyProfileService(limited, profiles, instruments,
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC), "NSE", "CASH")
                .refresh(List.of("AAA", "BBB", "CCC"), 0, WEEK, false);

        assertEquals(List.of("AAA", "BBB"), asked, "nothing is asked after the refusal");
        assertEquals(1, result.fetched());
        assertEquals(2, result.notAttempted());
        assertTrue(result.stoppedBy().contains("429"));
        assertTrue(profiles.fetchedAt(id("AAA")).isPresent(), "what was read before the stop is kept");
    }

    @Test
    @DisplayName("a new reading replaces the old one entirely, quarters and shareholding included")
    void savingReplaces() {
        long id = id("AAA");
        profiles.save(id, welspun, NOW - WEEK);
        assertEquals("8", scalar("SELECT COUNT(*) FROM company_quarter"));
        assertEquals("8", scalar("SELECT COUNT(*) FROM company_holding"));

        CompanyProfile shorter = new CompanyProfile("About.", "Sector", 100.0, 10.0, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                welspun.quarters().subList(0, 2), List.of());
        profiles.save(id, shorter, NOW);

        assertEquals("2", scalar("SELECT COUNT(*) FROM company_quarter"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM company_holding"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM company_profile"));
        assertEquals("About.", scalar("SELECT about FROM company_profile"));
    }

    @Test
    @DisplayName("price-dependent ratios are restated at the latest price from figures that do not move with it")
    void ratiosAreRestatedAtTheLatestPrice() {
        long id = id("WELSPUNLIV");
        profiles.save(id, welspun, NOW);   // read at Rs 254, market cap 23,968 Cr, EPS 4.57, book value 53.6

        // With no price of our own the page's own price stands.
        Map<String, Object> asRead = rows("SELECT * FROM v_company_fundamentals WHERE symbol = 'WELSPUNLIV'").getFirst();
        assertEquals(254.0, (double) asRead.get("price_now"), 1e-9);
        assertEquals(23_968.0, (double) asRead.get("market_cap_now_cr"), 1e-6);

        // The last session closed at double that: the market cap and P/E double, the yield halves.
        session(id, "2026-11-04", 508.0);
        Map<String, Object> doubled = rows("SELECT * FROM v_company_fundamentals WHERE symbol = 'WELSPUNLIV'").getFirst();
        assertEquals(508.0, (double) doubled.get("price_now"), 1e-9);
        assertEquals(47_936.0, (double) doubled.get("market_cap_now_cr"), 1e-6);
        assertEquals(508.0 / 4.57, (double) doubled.get("pe_now"), 1e-9);
        assertEquals(508.0 / 53.6, (double) doubled.get("pb_now"), 1e-9);
        assertEquals(0.04 / 2, (double) doubled.get("dividend_yield_now"), 1e-9);
        assertEquals(4.57, (double) doubled.get("eps_ttm"), 1e-9, "per-share earnings do not move with the price");

        // Today's live candle beats the last session's close.
        liveCandle(id, 300.0);
        assertEquals(300.0, (double) rows("SELECT price_now FROM v_company_fundamentals").getFirst().get("price_now"), 1e-9);
    }

    @Test
    @DisplayName("our own 52-week range is preferred to the page's rounded one, which is the fallback")
    void ourOwn52WeekRangeWins() {
        long id = id("WELSPUNLIV");
        profiles.save(id, welspun, NOW);
        assertEquals(267.0, (double) rows("SELECT high_52w FROM v_company_fundamentals").getFirst().get("high_52w"), 1e-9);

        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO symbol_week52 (instrument_id, week52_high, week52_low, sessions, computed_at)
                    VALUES (?, 267.42, 107.10, 250, 0)""")) {
                insert.setLong(1, id);
                insert.executeUpdate();
            }
        });
        Map<String, Object> own = rows("SELECT * FROM v_company_fundamentals").getFirst();
        assertEquals(267.42, (double) own.get("high_52w"), 1e-9);
        assertEquals(107.10, (double) own.get("low_52w"), 1e-9);
        assertEquals((254.0 / 267.42 - 1) * 100, (double) own.get("pct_from_52w_high"), 1e-9);
    }

    @Test
    @DisplayName("the panels return the fundamentals, the quarters, the shareholding, the growth and the about text")
    void thePanelsReadTheProfile() throws IOException {
        profiles.save(id("WELSPUNLIV"), welspun, NOW);

        Map<String, Object> tiles = rows(panel(30)).getFirst();
        assertEquals(13, tiles.size(), "one tile per figure");
        assertEquals(23_968.0, ((Number) tiles.get("Market cap (₹ Cr)")).doubleValue(), 1.0);
        assertEquals(55.6, ((Number) tiles.get("P/E")).doubleValue(), 0.05, "254 / 4.57");
        assertEquals(0.47, ((Number) tiles.get("Debt / equity")).doubleValue(), 0.005);
        assertEquals(4.57, ((Number) tiles.get("EPS (TTM)")).doubleValue(), 0.001);
        assertEquals(1.0, ((Number) tiles.get("Face value")).doubleValue(), 0.001);

        List<Map<String, Object>> quarters = rows(panel(31));
        assertEquals(8, quarters.size());
        assertEquals("Dec 2024", quarters.getFirst().get("Quarter"), "oldest first, month names");
        assertEquals("Sep 2026", quarters.getLast().get("Quarter"));
        assertEquals(2_838.0, ((Number) quarters.getLast().get("Revenue")).doubleValue(), 0.01);
        assertEquals(170.0, ((Number) quarters.getLast().get("Net profit")).doubleValue(), 0.01);

        Map<String, Object> holders = rows(panel(32)).getLast();
        assertEquals("Sep 2026", holders.get("Quarter"));
        assertEquals(63.77, ((Number) holders.get("Promoters")).doubleValue(), 0.001);
        assertEquals(9.07, ((Number) holders.get("FII")).doubleValue(), 0.001);

        Map<String, Object> growth = rows(panel(33)).getFirst();
        assertEquals(24.0, ((Number) growth.get("Profit growth (TTM)")).doubleValue(), 0.001);

        Map<String, Object> about = rows(panel(34)).getFirst();
        assertTrue(((String) about.get("About")).startsWith("Welspun Living Limited"));
        assertTrue(((String) about.get("Sector")).contains("Textiles"));
    }

    @Test
    @DisplayName("for a symbol whose page has not been read the panels are simply empty")
    void noProfileNoRows() throws IOException {
        id("WELSPUNLIV");
        for (int panel : new int[]{30, 31, 32, 33, 34}) {
            assertTrue(rows(panel(panel)).isEmpty(), "panel " + panel);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** What the fake client answers for a symbol. */
    private interface ProfileSource {
        Optional<CompanyProfile> profile(String symbol);
    }

    @Test
    @DisplayName("the slow round reads the symbols outside the tracked list, and never the tracked ones")
    void theSlowRoundLeavesTheTrackedAlone() {
        id("TRACKED");
        id("AAA");
        id("BBB");

        CompanyProfileService.Result result = service(symbol -> Optional.of(welspun))
                .refreshUntracked(List.of("TRACKED"), List.of(), 0, QUARTER_YEAR);

        assertEquals(List.of("AAA", "BBB"), asked, "TRACKED has its own weekly round");
        assertEquals(2, result.fetched());
    }

    @Test
    @DisplayName("a copy is read again only after three months, so one from last month is left alone")
    void aQuarterOfAYearBetweenReads() {
        profiles.save(id("RECENT"), welspun, NOW - 30 * 86_400L);
        profiles.save(id("OLD"), welspun, NOW - 100 * 86_400L);
        profiles.save(id("EDGE"), welspun, NOW - 90 * 86_400L);

        service(symbol -> Optional.of(welspun)).refreshUntracked(List.of(), List.of(), 0, QUARTER_YEAR);

        assertEquals(List.of("OLD", "EDGE"), asked, "the oldest first; 90 days old is due, 30 days old is not");
    }

    @Test
    @DisplayName("a few pages a night: never-read first, then the oldest, the rest left for a later night")
    void partByPart() {
        id("NEW1");
        id("NEW2");
        profiles.save(id("OLDEST"), welspun, NOW - 200 * 86_400L);
        profiles.save(id("OLDER"), welspun, NOW - 150 * 86_400L);

        CompanyProfileService.Result result = service(symbol -> Optional.of(welspun))
                .refreshUntracked(List.of(), List.of(), 3, QUARTER_YEAR);

        assertEquals(List.of("NEW1", "NEW2", "OLDEST"), asked);
        assertEquals(3, result.fetched());
        assertEquals(1, result.notAttempted(), "OLDER waits for tomorrow night");
    }

    @Test
    @DisplayName("a stock you traded comes ahead of the never-read rest, and shares the same page budget")
    void tradedStocksFirst() {
        id("AAA");
        id("BBB");
        id("TRADED");
        profiles.save(id("TRADEDOLD"), welspun, NOW - 200 * 86_400L);

        CompanyProfileService.Result result = service(symbol -> Optional.of(welspun))
                .refreshUntracked(List.of(), List.of("traded", "TRADEDOLD"), 3, QUARTER_YEAR);

        assertEquals(List.of("TRADED", "TRADEDOLD", "AAA"), asked,
                "traded ones first (never-read, then oldest, whatever the case of the name), then the rest");
        assertEquals(1, result.notAttempted());
    }

    @Test
    @DisplayName("an instrument of another exchange or segment is not read, whatever its name")
    void onlyThisMarket() {
        id("AAA");
        instruments.findOrCreate("FUT1", "NSE", "FNO");
        instruments.findOrCreate("BSE1", "BSE", "CASH");

        service(symbol -> Optional.of(welspun)).refreshUntracked(List.of(), List.of(), 0, QUARTER_YEAR);

        assertEquals(List.of("AAA"), asked);
    }

    @Test
    @DisplayName("a refusal ends the slow round, keeps what was read, and says how much is still due")
    void aRefusalEndsTheSlowRound() {
        id("AAA");
        id("BBB");
        id("CCC");
        CompanyFundamentalsClient limited = new CompanyFundamentalsClient() {
            @Override
            public Optional<CompanyFundamentals> fetch(String symbol) {
                return Optional.empty();
            }

            @Override
            public Optional<CompanyProfile> fetchProfile(String symbol) {
                asked.add(symbol);
                if (symbol.equals("BBB")) {
                    throw new ScreenerException("screener.in is still rate limiting (HTTP 429)", false);
                }
                return Optional.of(welspun);
            }
        };

        CompanyProfileService.Result result = new CompanyProfileService(limited, profiles, instruments,
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC), "NSE", "CASH")
                .refreshUntracked(List.of(), List.of(), 10, QUARTER_YEAR);

        assertEquals(List.of("AAA", "BBB"), asked, "nothing is asked after the refusal");
        assertEquals(1, result.fetched());
        assertEquals(2, result.notAttempted());
        assertTrue(result.stoppedBy().contains("429"));
        assertTrue(profiles.fetchedAt(id("AAA")).isPresent());
        assertTrue(profiles.fetchedAt(id("CCC")).isEmpty(), "CCC is picked up on a later night");
    }

    @Test
    @DisplayName("a page with nothing on it is remembered for three months, not asked again each night")
    void anEmptyPageIsRememberedForAQuarter() {
        long id = id("ETFX");

        service(symbol -> Optional.empty()).refreshUntracked(List.of(), List.of(), 0, QUARTER_YEAR);
        assertEquals(Optional.of(NOW), profiles.fetchedAt(id));

        asked.clear();
        service(symbol -> Optional.empty()).refreshUntracked(List.of(), List.of(), 0, QUARTER_YEAR);
        assertTrue(asked.isEmpty());
    }

    private CompanyProfileService service(ProfileSource source) {
        CompanyFundamentalsClient client = new CompanyFundamentalsClient() {
            @Override
            public Optional<CompanyFundamentals> fetch(String symbol) {
                return Optional.empty();
            }

            @Override
            public Optional<CompanyProfile> fetchProfile(String symbol) {
                asked.add(symbol);
                return source.profile(symbol);
            }
        };
        return new CompanyProfileService(client, profiles, instruments,
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC), "NSE", "CASH");
    }

    private long id(String symbol) {
        return instruments.findOrCreate(symbol, "NSE", "CASH");
    }

    private void session(long instrumentId, String date, double close) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO trading_day (instrument_id, session_date, interval_minutes, open, high, low, close,
                                             volume, candle_count, source, ingested_at)
                    VALUES (?,?,1,?,?,?,?,1000,375,'test',0)""")) {
                insert.setLong(1, instrumentId);
                insert.setString(2, date);
                insert.setDouble(3, close);
                insert.setDouble(4, close);
                insert.setDouble(5, close);
                insert.setDouble(6, close);
                insert.executeUpdate();
            }
        });
    }

    private void liveCandle(long instrumentId, double close) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, open, high, low,
                                             close, volume, provisional, updated_at)
                    VALUES (?,1,1793100000,'2026-11-05',?,?,?,?,1000,0,0)""")) {
                insert.setLong(1, instrumentId);
                insert.setDouble(2, close);
                insert.setDouble(3, close);
                insert.setDouble(4, close);
                insert.setDouble(5, close);
                insert.executeUpdate();
            }
        });
    }

    private static String panel(int id) throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        return StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == id).findFirst().orElseThrow()
                .path("targets").get(0).path("queryText").asText().replace("$symbol", "WELSPUNLIV");
    }

    private String scalar(String sql) {
        List<Map<String, Object>> rows = rows(sql);
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().values().iterator().next());
    }

    private List<Map<String, Object>> rows(String sql) {
        return database.read(connection -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
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
