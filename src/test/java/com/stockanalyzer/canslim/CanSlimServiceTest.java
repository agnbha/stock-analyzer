package com.stockanalyzer.canslim;

import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.client.GrowwSymbolRejectedException;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.OpenPosition;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.RealizedLot;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.RealizedLotRepository;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteCanSlimRepository;
import com.stockanalyzer.store.jdbc.SqliteDailyBarRepository;
import com.stockanalyzer.store.jdbc.SqliteFundamentalsCacheRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteMarketRejectRepository;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanSlimServiceTest {

    private static final MarketClock MARKET = MarketClock.nse();

    @TempDir
    Path directory;

    private Database database;
    private ExecutorService executor;

    /** Moves when a test moves it; the scan reads the date from it. */
    private Instant now = Instant.parse("2025-08-01T13:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        executor = Executors.newFixedThreadPool(3);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        database.close();
    }

    @Test
    @DisplayName("a scan screens, prices, scores and stores - buys for candidates, sells for holdings")
    void endToEnd() {
        Map<String, List<DailyBar>> prices = Map.of(
                "IDX", TestBars.from(100).trend(130, 112).build(),
                "GROW", TestBars.from(100).trend(150, 70).range(138, 146, 38).add(152, 2000).build(),
                "HELD", TestBars.from(100).trend(120, 100).trend(108, 12).build(),
                "LAG", TestBars.from(100).trend(95, 112).build());
        ScreenedStock grow = new ScreenedStock("GROW", "Grower", Map.of(
                ScreenerRatios.QTR_PROFIT_GROWTH, 40.0, ScreenerRatios.PROFIT_GROWTH_3Y, 30.0));

        CanSlimService service = service(query -> new ScreenerClient.Result(List.of(grow, new ScreenedStock("GONE", "Delisted", Map.of())), false),
                prices, List.of(new OpenPosition("HELD", Product.CNC, 10, 121.0, 0)), List.of("LAG"));

        CanSlimReport report = service.scan("Return on equity > 17");

        assertEquals(MarketState.CONFIRMED_UPTREND, report.market().state());
        assertEquals(2, report.screened());
        Map<String, CanSlimScore> bySymbol = report.scores().stream()
                .collect(java.util.stream.Collectors.toMap(CanSlimScore::symbol, Function.identity()));
        assertEquals(CanSlimSignal.BUY_BREAKOUT, bySymbol.get("GROW").signal());
        assertEquals(CanSlimSignal.SELL_STOP_LOSS, bySymbol.get("HELD").signal());
        assertEquals(CanSlimScore.Source.HOLDING, bySymbol.get("HELD").source());
        assertEquals(CanSlimSignal.NONE, bySymbol.get("GONE").signal(), "no prices, no signal");
        assertEquals(1, report.failures().size());
        assertTrue(report.failures().getFirst().startsWith("GONE"));
        assertEquals(CanSlimSignal.SELL_STOP_LOSS, report.scores().getFirst().signal(), "sells are listed first");

        assertEquals(4, count("SELECT COUNT(*) FROM canslim_score"));
        assertEquals("BUY_BREAKOUT", text("SELECT signal FROM v_canslim_latest WHERE symbol = 'GROW'"));
        assertEquals(150.0, Double.parseDouble(text("SELECT pivot FROM v_canslim_latest WHERE symbol = 'GROW'")));
        assertEquals(prices.get("GROW").size(),
                count("SELECT COUNT(*) FROM v_daily_bars WHERE symbol = 'GROW'"));
        assertEquals(1, count("SELECT COUNT(*) FROM v_daily_bars WHERE symbol = 'GROW' AND sma50 IS NOT NULL "
                + "AND session_date = '" + prices.get("GROW").getLast().date() + "'"));
    }

    @Test
    @DisplayName("without a query only holdings and the universe are scored")
    void noScreener() {
        CanSlimService service = service(query -> {
            throw new AssertionError("the screener must not be called");
        }, Map.of("IDX", TestBars.from(100).trend(130, 112).build(),
                "LAG", TestBars.from(100).trend(95, 112).build()), List.of(), List.of("LAG"));

        CanSlimReport report = service.scan(null);

        assertEquals(0, report.screened());
        assertEquals(List.of("LAG"), report.scores().stream().map(CanSlimScore::symbol).toList());
    }

    private final List<String> pagesRead = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> pricedOn = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Symbols the fake candle API answers GA001 for, and ones it knows but has no bars for. */
    private final java.util.Set<String> refusedSymbols = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> emptySymbols = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Pages one scan may read; generous unless a test is about the limit. */
    private int pageBudget = 1_000;

    /** Company pages by symbol; a symbol not in the map has no page. Every page read is recorded. */
    private CompanyFundamentalsClient pages(Map<String, Map<String, Double>> byLabel) {
        return symbol -> {
            pagesRead.add(symbol);
            return Optional.ofNullable(byLabel.get(symbol)).map(CanSlimServiceTest::reported);
        };
    }

    /** Figures from a company that has reported the June 2025 quarter, the latest one to have ended in these tests. */
    private static CompanyFundamentals reported(Map<String, Double> ratios) {
        return new CompanyFundamentals(ratios, YearMonth.of(2025, 6), YearMonth.of(2025, 6));
    }

    /** The same figures from a company that has not yet reported June: its newest quarter is March. */
    private static CompanyFundamentals notYetReported(Map<String, Double> ratios) {
        return new CompanyFundamentals(ratios, YearMonth.of(2025, 3), YearMonth.of(2025, 6));
    }

    private static final Map<String, Double> STRONG = Map.of(
            ScreenerRatios.QTR_PROFIT_GROWTH, 40.0, ScreenerRatios.QTR_SALES_GROWTH, 28.0,
            ScreenerRatios.PROFIT_GROWTH_3Y, 32.0, ScreenerRatios.ROE, 22.0,
            ScreenerRatios.FII_CHANGE, 0.8, ScreenerRatios.DII_CHANGE, 0.1);
    private static final Map<String, Double> WEAK = Map.of(
            ScreenerRatios.QTR_PROFIT_GROWTH, 4.0, ScreenerRatios.PROFIT_GROWTH_3Y, 6.0);

    /** A run-up to {@code peak}, eight weeks of base under it, then a close through it on double volume. */
    private static List<DailyBar> breakoutOver(double peak) {
        return TestBars.from(100).trend(peak, 70).range(peak * 0.92, peak * 0.973, 38)
                .add(peak * 1.013, 2000).build();
    }

    /** A client that was never given a session, so it cannot run a query. */
    private static ScreenerClient noSession() {
        return new ScreenerClient() {
            @Override
            public Result screen(String query) {
                throw new AssertionError("a query must not be attempted without a session");
            }

            @Override
            public boolean hasSession() {
                return false;
            }
        };
    }

    @Test
    @DisplayName("without a screener session the universe is still scored, from public company pages")
    void noSessionUsesCompanyPages() {
        Map<String, List<DailyBar>> prices = Map.of(
                "IDX", TestBars.from(100).trend(130, 112).build(),
                // Different run-ups, so relative strength has a spread to rank: GROW leads.
                "GROW", breakoutOver(180),
                "DULL", breakoutOver(150),
                "NOPAGE", breakoutOver(140));

        CanSlimReport report = service(noSession(), pages(Map.of("GROW", STRONG, "DULL", WEAK)), prices,
                List.of(), List.of("GROW", "DULL", "NOPAGE")).scan("Return on equity > 17");

        Map<String, CanSlimScore> bySymbol = report.scores().stream()
                .collect(java.util.stream.Collectors.toMap(CanSlimScore::symbol, Function.identity()));
        assertTrue(report.screenerNotice().contains("No screener.in session"), report.screenerNotice());
        assertFalse(report.screenerFailed(), "not having a session is a choice, not a failure");
        assertEquals(0, report.screened());

        CanSlimScore grow = bySymbol.get("GROW");
        assertEquals(Boolean.TRUE, grow.letters().c());
        assertEquals(Boolean.TRUE, grow.letters().a());
        assertEquals(Boolean.TRUE, grow.letters().i());
        assertEquals(CanSlimSignal.BUY_BREAKOUT, grow.signal(), "strong earnings and a breakout is a buy");

        assertEquals(Boolean.FALSE, bySymbol.get("DULL").letters().c());
        assertEquals(CanSlimSignal.NONE, bySymbol.get("DULL").signal(), "same chart, weak earnings");
        assertNull(bySymbol.get("NOPAGE").letters().c());
        assertEquals(CanSlimSignal.NONE, bySymbol.get("NOPAGE").signal(), "no fundamentals, so sell-only");
        assertTrue(report.fundamentalsNotice().contains("3 fetched"), report.fundamentalsNotice());
    }

    @Test
    @DisplayName("fundamentals are reused for the cache window, so the site is not read every night")
    void pagesAreCached() {
        Map<String, List<DailyBar>> prices = Map.of(
                "IDX", TestBars.from(100).trend(130, 112).build(),
                "GROW", TestBars.from(100).trend(120, 112).build());
        CanSlimService service = service(noSession(), pages(Map.of("GROW", STRONG)), prices, List.of(),
                List.of("GROW"));

        service.scan("q");
        CanSlimReport second = service.scan("q");

        assertEquals(List.of("GROW"), pagesRead, "read once, not once per scan");
        assertTrue(second.fundamentalsNotice().contains("1 reused"), second.fundamentalsNotice());
        assertEquals(Boolean.TRUE, second.scores().getFirst().letters().c(), "and still known from the cache");
    }

    @Test
    @DisplayName("in reporting season a company yet to report is looked at again every couple of days, then left alone")
    void seasonRechecksUntilTheQuarterAppears() {
        Map<String, List<DailyBar>> prices = Map.of(
                "IDX", TestBars.from(100).trend(130, 112).build(),
                "LATE", TestBars.from(100).trend(120, 112).build());
        java.util.concurrent.atomic.AtomicBoolean reportedNow = new java.util.concurrent.atomic.AtomicBoolean();
        CompanyFundamentalsClient page = symbol -> {
            pagesRead.add(symbol);
            return Optional.of(reportedNow.get() ? reported(STRONG) : notYetReported(STRONG));
        };
        CanSlimService service = service(noSession(), page, prices, List.of(), List.of("LATE"));

        CanSlimReport first = service.scan("q");
        service.scan("q");
        assertEquals(List.of("LATE"), pagesRead, "the same day: nothing to learn yet");
        assertTrue(first.fundamentalsNotice().contains("1 still showing an earlier quarter than Jun 2025"),
                first.fundamentalsNotice());

        now = now.plusSeconds(3 * 86_400L);
        service.scan("q");
        assertEquals(List.of("LATE", "LATE"), pagesRead, "three days on: it may have reported");

        reportedNow.set(true);
        now = now.plusSeconds(3 * 86_400L);
        CanSlimReport caughtUp = service.scan("q");
        assertEquals(3, pagesRead.size(), "read once more and found June");
        assertTrue(caughtUp.fundamentalsNotice().contains("0 still showing"), caughtUp.fundamentalsNotice());

        now = now.plusSeconds(30 * 86_400L);
        service.scan("q");
        assertEquals(3, pagesRead.size(), "a month later, with June in hand, the page is not read");
    }

    @Test
    @DisplayName("a query with more results than one page says so, without calling the scan a failure")
    void moreThanOnePageIsReported() {
        Map<String, List<DailyBar>> prices = Map.of(
                "IDX", TestBars.from(100).trend(130, 112).build(),
                "GROW", TestBars.from(100).trend(150, 70).range(138, 146, 38).add(152, 2000).build());
        ScreenedStock grow = new ScreenedStock("GROW", "Grower", Map.of(
                ScreenerRatios.QTR_PROFIT_GROWTH, 40.0, ScreenerRatios.PROFIT_GROWTH_3Y, 30.0));

        CanSlimReport report = service(query -> new ScreenerClient.Result(List.of(grow), true),
                prices, List.of(), List.of()).scan("q");

        assertEquals(1, report.screened());
        assertTrue(report.screenerNotice().contains("more than one page"), report.screenerNotice());
        assertTrue(report.screenerNotice().contains("robots.txt"), report.screenerNotice());
        assertFalse(report.screenerFailed());
    }

    private static List<DailyBar> thinlyTraded(List<DailyBar> bars) {
        return bars.stream().map(b -> new DailyBar(b.date(), b.open(), b.high(), b.low(), b.close(), 10)).toList();
    }

    private static MarketListing listing(String symbol, String exchange, String screenerKey) {
        return new MarketListing(symbol, exchange, symbol + " Ltd", "INE" + symbol, "EQ", screenerKey);
    }

    private static final double FLOOR = 1000;

    /** Strong figures for a company of the given market capitalisation, in rupees crore. */
    private static Map<String, Double> withCap(double capCr) {
        Map<String, Double> figures = new java.util.HashMap<>(STRONG);
        figures.put(ScreenerRatios.MARKET_CAP, capCr);
        return figures;
    }

    /** Thirteen months of data would be realistic; the tests need only enough for the rules to run. */
    private Map<String, List<DailyBar>> marketPrices() {
        Map<String, List<DailyBar>> prices = new java.util.HashMap<>();
        prices.put("IDX", TestBars.from(100).trend(130, 130).build());
        prices.put("BSEONLY", TestBars.from(100).trend(260, 130).build());
        prices.put("LEAD", TestBars.from(100).trend(200, 130).build());
        prices.put("MID", TestBars.from(100).trend(150, 130).build());
        prices.put("WEAK", TestBars.from(100).trend(80, 130).build());
        prices.put("FELL", TestBars.from(100).trend(150, 70).range(138, 146, 38).add(152, 2000).trend(130, 20).build());
        prices.put("THIN", thinlyTraded(TestBars.from(100).trend(300, 130).build()));
        prices.put("NEWIPO", TestBars.from(100).trend(400, 40).build());
        return prices;
    }

    /** Ranked by return: BSEONLY 99, LEAD 75, MID 50, FELL 26, WEAK 1 - until a small company leaves the field. */
    private MarketScan wholeMarket(int keepRuns, int fundamentalsMinRs) {
        return new MarketScan(List.of(
                listing("LEAD", "NSE", "LEAD"), listing("MID", "NSE", "MID"), listing("WEAK", "NSE", "WEAK"),
                listing("FELL", "NSE", "FELL"), listing("THIN", "NSE", "THIN"), listing("NEWIPO", "NSE", "NEWIPO"),
                listing("DEAD", "NSE", "DEAD"), listing("BSEONLY", "BSE", "500001")),
                100, 10.0, 50_000.0, 10, fundamentalsMinRs, keepRuns, FLOOR);
    }

    private MarketScan wholeMarket(int keepRuns) {
        return wholeMarket(keepRuns, 50);
    }

    private CanSlimService marketService(Map<String, Map<String, Double>> pages) {
        now = Instant.parse("2025-05-12T13:00:00Z");
        return service(noSession(), pages(pages), marketPrices(), List.of(), List.of());
    }

    private static CanSlimScore score(CanSlimReport report, String symbol) {
        return report.scores().stream().filter(s -> s.symbol().equals(symbol)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("only companies confirmed above the market-cap floor are selected; the rest are dropped or left unchecked")
    void marketScanSelectsOnlyConfirmedLargeCompanies() {
        CanSlimReport report = marketService(Map.of(
                "500001", withCap(5000), "LEAD", withCap(800), "MID", withCap(1500)))
                .scan("q", wholeMarket(5));

        Map<String, CanSlimScore> bySymbol = report.scores().stream()
                .collect(java.util.stream.Collectors.toMap(CanSlimScore::symbol, Function.identity()));
        assertEquals(java.util.Set.of("BSEONLY", "MID"), bySymbol.keySet(),
                "LEAD is under 1,000 Cr, and FELL and WEAK rank too low for their size to have been checked");
        assertTrue(bySymbol.values().stream().allMatch(s -> s.source() == CanSlimScore.Source.MARKET));
        assertEquals(List.of("500001", "LEAD", "MID"), pagesRead, "strongest RS first, one page each");

        MarketSummary summary = report.marketSummary();
        assertEquals(8, summary.listed());
        assertEquals(1, summary.failed(), "DEAD has no candles");
        assertEquals(5, summary.ranked(), "the field: liquid listings with enough history");
        assertEquals(2, summary.selected());
        assertEquals(2, summary.unchecked(), "FELL and WEAK: ranked, but their size was never looked up");
        assertEquals(Map.of("illiquid", 1, "short history", 1, "market cap at or under 1,000 Cr", 1),
                summary.unranked());
    }

    @Test
    @DisplayName("a company found to be small leaves the field, so the ratings that remain are against the larger ones")
    void foundSmallCompaniesAreRankedOut() {
        CanSlimReport report = marketService(Map.of(
                "500001", withCap(5000), "LEAD", withCap(800), "MID", withCap(1500)))
                .scan("q", wholeMarket(5));

        assertEquals(99, score(report, "BSEONLY").rsRating());
        assertEquals(66, score(report, "MID").rsRating(),
                "third of five (50) before LEAD left; second of four (66) after");
    }

    @Test
    @DisplayName("a size learned once is remembered: the next scan starts without the small company in the field")
    void smallCompaniesAreRemembered() {
        CanSlimService service = marketService(Map.of(
                "500001", withCap(5000), "LEAD", withCap(800), "MID", withCap(1500)));

        service.scan("q", wholeMarket(5));
        CanSlimReport second = service.scan("q", wholeMarket(5));

        assertEquals(4, second.marketSummary().ranked(), "LEAD is out of the field before ranking begins");
        assertEquals(1, second.marketSummary().unranked().get("market cap at or under 1,000 Cr"));
        assertEquals(66, score(second, "MID").rsRating());
        assertEquals(List.of("500001", "LEAD", "MID"), pagesRead, "and nothing is read twice");
    }

    @Test
    @DisplayName("a listing whose page has not been read is not selected, however strong it ranks")
    void unverifiedListingsAreNotSelected() {
        CanSlimReport report = marketService(Map.of()).scan("q", wholeMarket(5));

        assertTrue(report.scores().isEmpty(), "no page, no market cap, nothing selected");
        assertEquals(0, report.marketSummary().selected());
        assertEquals(5, report.marketSummary().unchecked());
    }

    @Test
    @DisplayName("a BSE-only listing is priced on BSE and its company page is asked for by scrip code")
    void bseOnlyListingUsesItsOwnExchangeAndKey() {
        CanSlimReport report = marketService(Map.of("500001", withCap(5000))).scan("q", wholeMarket(5));

        assertTrue(pricedOn.contains("BSEONLY@BSE"), pricedOn.toString());
        assertTrue(pricedOn.contains("LEAD@NSE"), pricedOn.toString());
        assertEquals("BSE", text("SELECT exchange FROM instrument WHERE symbol = 'BSEONLY'"));
        assertEquals("500001", pagesRead.getFirst());
        CanSlimScore bseOnly = score(report, "BSEONLY");
        assertEquals(Boolean.TRUE, bseOnly.letters().c());
        assertEquals(Boolean.TRUE, bseOnly.letters().l());
    }

    @Test
    @DisplayName("only listings that rank above the RS bar have their company page read")
    void fundamentalsOnlyForLeaders() {
        CanSlimReport report = marketService(Map.of("500001", withCap(5000), "LEAD", withCap(5000),
                "MID", withCap(5000))).scan("q", wholeMarket(5, 80));

        assertEquals(List.of("500001"), pagesRead, "one page, not eight: LEAD and MID rank below 80");
        assertEquals(1, report.marketSummary().fundamentalsRequested());
        assertTrue(report.fundamentalsNotice().contains("Market leaders (RS 80+)"), report.fundamentalsNotice());
        assertEquals(java.util.Set.of("BSEONLY"), report.scores().stream().map(CanSlimScore::symbol)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    @DisplayName("nobody holds a market listing, so a breakout that fell back is not a sell")
    void listingsNeverGetSellSignals() {
        Map<String, Map<String, Double>> pages = new java.util.HashMap<>();
        for (String key : List.of("500001", "LEAD", "MID", "FELL", "WEAK")) {
            pages.put(key, withCap(5000));
        }
        now = Instant.parse("2025-05-12T13:00:00Z");
        Map<String, List<DailyBar>> prices = marketPrices();

        CanSlimReport asListing = service(noSession(), pages(pages), prices, List.of(), List.of())
                .scan("q", wholeMarket(5, 1));
        CanSlimReport asUniverse = service(noSession(), pages(pages), prices, List.of(), List.of("FELL"))
                .scan("q");

        CanSlimScore listed = score(asListing, "FELL");
        assertEquals(CanSlimScore.Source.MARKET, listed.source());
        assertFalse(listed.signal().name().startsWith("SELL"), listed.signal().toString());
        assertTrue(asUniverse.scores().stream().filter(s -> s.symbol().equals("FELL"))
                        .anyMatch(s -> s.signal().name().startsWith("SELL")),
                "the same chart, watched by name, is a sell - which is what the guard is for");
    }

    @Test
    @DisplayName("only the newest few scans keep their market-wide scores")
    void oldMarketScoresArePruned() {
        CanSlimService service = marketService(Map.of("500001", withCap(5000)));

        for (int i = 0; i < 4; i++) {
            service.scan("q", wholeMarket(2));
        }

        assertEquals(2, count("SELECT COUNT(DISTINCT run_id) FROM canslim_score WHERE source = 'MARKET'"));
        assertEquals(4, count("SELECT COUNT(*) FROM canslim_run"), "the runs themselves are kept");
    }

    @Test
    @DisplayName("the report lists the strongest by RS, strongest first, from the whole field")
    void reporterSortsByRelativeStrength() {
        CanSlimReport report = marketService(Map.of("500001", withCap(5000), "LEAD", withCap(5000),
                "MID", withCap(5000))).scan("q", wholeMarket(5));

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        new com.stockanalyzer.report.CanSlimReporter(new java.io.PrintStream(bytes, true)).report(report, false, 2);
        String out = bytes.toString();
        String strongest = out.substring(out.indexOf("Strongest by relative strength"));

        assertTrue(strongest.contains("top 2 of 3 rated"), strongest);
        int bseOnly = strongest.indexOf("BSEONLY");
        int lead = strongest.indexOf("LEAD");
        assertTrue(bseOnly > 0 && bseOnly < lead, strongest);
        assertFalse(strongest.contains("MID"), "only the top two are listed");
        assertTrue(out.contains("Market-wide: 8 listed equities; 5 in the RS field, 1 could not be priced; "
                + "3 selected"), out);
    }

    @Test
    @DisplayName("a scan reads no more pages than its budget, and spends it on the strongest RS first")
    void pageBudgetGoesToTheStrongest() {
        now = Instant.parse("2025-05-12T13:00:00Z");
        pageBudget = 1;
        Map<String, List<DailyBar>> prices = new java.util.HashMap<>();
        prices.put("IDX", TestBars.from(100).trend(130, 130).build());
        for (int i = 1; i <= 6; i++) {
            prices.put("S" + i, TestBars.from(100).trend(100 + 40 * i, 130).build());
        }
        MarketScan six = new MarketScan(List.of(listing("S1", "NSE", "S1"), listing("S2", "NSE", "S2"),
                listing("S3", "NSE", "S3"), listing("S4", "NSE", "S4"), listing("S5", "NSE", "S5"),
                listing("S6", "NSE", "S6")), 100, 10.0, 50_000.0, 10, 50, 5, FLOOR);
        Map<String, Map<String, Double>> pages = new java.util.HashMap<>();
        prices.keySet().forEach(symbol -> pages.put(symbol, withCap(5000)));

        CanSlimReport report = service(noSession(), pages(pages), prices, List.of(), List.of()).scan("q", six);

        assertEquals(List.of("S6"), pagesRead, "the one page went to the highest RS rating");
        assertTrue(report.fundamentalsNotice().contains("1-page budget"), report.fundamentalsNotice());
        assertEquals(Boolean.TRUE, score(report, "S6").letters().c());
        assertNull(score(report, "S5"), "next in line: not read yet, so its size is not confirmed and it is not selected");
    }

    @Test
    @DisplayName("a rejected session is reported, but holdings are still scored for sell signals")
    void expiredSessionDegrades() {
        ScreenerClient expired = new ScreenerClient() {
            @Override
            public Result screen(String query) {
                throw new ScreenerException("screener.in did not accept the session (HTTP 302)", true);
            }
        };
        Map<String, List<DailyBar>> prices = Map.of(
                "IDX", TestBars.from(100).trend(130, 112).build(),
                "HELD", TestBars.from(100).trend(120, 100).trend(108, 12).build());

        CanSlimReport report = service(expired, prices,
                List.of(new OpenPosition("HELD", Product.CNC, 10, 121.0, 0)), List.of()).scan("q");

        assertTrue(report.screenerFailed());
        assertTrue(report.needsLogin());
        assertTrue(report.screenerNotice().contains("did not accept the session"));
        assertEquals(CanSlimSignal.SELL_STOP_LOSS, report.scores().getFirst().signal());
    }

    @Test
    @DisplayName("being rate limited stops further page reads instead of asking again")
    void rateLimitStopsFetching() {
        Map<String, List<DailyBar>> prices = Map.of(
                "IDX", TestBars.from(100).trend(130, 112).build(),
                "A", TestBars.from(100).trend(120, 112).build(),
                "B", TestBars.from(100).trend(120, 112).build(),
                "C", TestBars.from(100).trend(120, 112).build());
        CompanyFundamentalsClient limited = symbol -> {
            pagesRead.add(symbol);
            if (symbol.equals("B")) {
                throw new ScreenerException("screener.in is rate limiting (HTTP 429)", false);
            }
            return Optional.of(reported(STRONG));
        };

        CanSlimReport report = service(noSession(), limited, prices, List.of(), List.of("A", "B", "C")).scan("q");

        assertEquals(List.of("A", "B"), pagesRead, "C was never asked for");
        assertTrue(report.fundamentalsNotice().contains("stopped early"), report.fundamentalsNotice());
        assertEquals(3, report.scores().size(), "all three still scored on their charts");
    }

    private static MarketScan remembering(MarketScan scan, int days) {
        return new MarketScan(scan.listings(), scan.minSessions(), scan.minPrice(), scan.minTurnover(),
                scan.maxStaleDays(), scan.fundamentalsMinRs(), scan.keepRuns(), scan.minMarketCapCr(), days);
    }

    private int timesAsked(String symbol, String exchange) {
        return java.util.Collections.frequency(pricedOn, symbol + "@" + exchange);
    }

    @Test
    @DisplayName("a listing Groww refuses is remembered, and the next scan does not ask about it")
    void refusedListingIsNotAskedAgain() {
        refusedSymbols.add("NEWIPO");
        CanSlimService service = marketService(Map.of("MID", withCap(5000)));
        MarketScan market = remembering(wholeMarket(5), 30);

        CanSlimReport first = service.scan("q", market);
        assertEquals(1, first.marketSummary().newlyRejected());
        assertEquals(0, first.marketSummary().skippedRejected());
        assertEquals("NEWIPO|NSE|GA001", text("SELECT symbol || '|' || exchange || '|' || reason FROM market_reject"));

        now = now.plusSeconds(86_400L);
        CanSlimReport second = service.scan("q", market);

        assertEquals(1, timesAsked("NEWIPO", "NSE"), "asked once, then left alone");
        assertEquals(1, second.marketSummary().skippedRejected());
        assertEquals(0, second.marketSummary().newlyRejected());
        assertEquals(2, timesAsked("LEAD", "NSE"), "everything else is asked about every scan");

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        new com.stockanalyzer.report.CanSlimReporter(new java.io.PrintStream(bytes, true)).report(second, false, 2);
        assertTrue(bytes.toString().contains("1 skipped as refused by Groww within the last 30 days"), bytes.toString());
    }

    @Test
    @DisplayName("a failure that may pass - an outage, a rate limit - is not taken for a dead listing")
    void transientFailureIsNotRemembered() {
        CanSlimService service = marketService(Map.of("MID", withCap(5000)));
        MarketScan market = remembering(wholeMarket(5), 30);

        service.scan("q", market);
        service.scan("q", market);

        // DEAD is in the list but unknown to the fake API, which fails it the way a dropped connection would.
        assertEquals(2, timesAsked("DEAD", "NSE"));
        assertEquals(0, count("SELECT COUNT(*) FROM market_reject"));
    }

    @Test
    @DisplayName("a listing that comes back with no bars is remembered too")
    void emptyAnswerIsRemembered() {
        emptySymbols.add("WEAK");
        CanSlimService service = marketService(Map.of("MID", withCap(5000)));

        service.scan("q", remembering(wholeMarket(5), 30));

        assertEquals("WEAK|NO_CANDLES", text("SELECT symbol || '|' || reason FROM market_reject"));
    }

    @Test
    @DisplayName("after the memory runs out the listing is asked about again, and forgotten once it is served")
    void memoryRunsOut() {
        refusedSymbols.add("NEWIPO");
        CanSlimService service = marketService(Map.of("MID", withCap(5000)));
        MarketScan market = remembering(wholeMarket(5), 30);
        service.scan("q", market);

        now = now.plusSeconds(29 * 86_400L);
        service.scan("q", market);
        assertEquals(1, timesAsked("NEWIPO", "NSE"), "29 days on it is still remembered");

        now = now.plusSeconds(2 * 86_400L);
        service.scan("q", market);
        assertEquals(2, timesAsked("NEWIPO", "NSE"), "31 days on it is asked about again");
        assertEquals("2", text("SELECT times FROM market_reject WHERE symbol = 'NEWIPO'"), "and refused again");

        now = now.plusSeconds(31 * 86_400L);
        refusedSymbols.clear();
        service.scan("q", market);
        assertEquals(3, timesAsked("NEWIPO", "NSE"));
        assertEquals(0, count("SELECT COUNT(*) FROM market_reject"), "served again, so forgotten");
    }

    @Test
    @DisplayName("with the memory set to 0 every listing is asked about every scan")
    void noMemory() {
        refusedSymbols.add("NEWIPO");
        CanSlimService service = marketService(Map.of("MID", withCap(5000)));

        service.scan("q", wholeMarket(5));
        service.scan("q", wholeMarket(5));

        assertEquals(2, timesAsked("NEWIPO", "NSE"));
        assertEquals(0, count("SELECT COUNT(*) FROM market_reject"));
    }

    @Test
    @DisplayName("a holding or configured symbol is always priced, even when it is also on the refused list")
    void onlyListingsAreSkipped() {
        refusedSymbols.add("FELL");
        now = Instant.parse("2025-05-12T13:00:00Z");
        CanSlimService service = service(noSession(), pages(Map.of()), marketPrices(), List.of(), List.of("FELL"));
        MarketScan market = remembering(wholeMarket(5), 30);

        service.scan("q", market);
        service.scan("q", market);

        assertEquals(2, timesAsked("FELL", "NSE"));
        assertEquals(0, count("SELECT COUNT(*) FROM market_reject"), "a configured symbol is not a listing");
    }

    @Test
    @DisplayName("if far too many listings are refused at once, nothing is remembered: the fault is not theirs")
    void aSystemicFailureIsNotRemembered() {
        now = Instant.parse("2025-05-12T13:00:00Z");
        Map<String, List<DailyBar>> prices = new java.util.HashMap<>();
        prices.put("IDX", TestBars.from(100).trend(130, 130).build());
        List<MarketListing> listings = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            listings.add(listing("R" + i, "NSE", "R" + i));
            refusedSymbols.add("R" + i);
        }
        for (int i = 1; i <= 10; i++) {
            listings.add(listing("G" + i, "NSE", "G" + i));
            prices.put("G" + i, TestBars.from(100).trend(100 + 10 * i, 130).build());
        }
        MarketScan market = new MarketScan(listings, 100, 10.0, 50_000.0, 10, 50, 5, FLOOR, 30);

        CanSlimReport report = service(noSession(), pages(Map.of()), prices, List.of(), List.of()).scan("q", market);

        assertEquals(0, report.marketSummary().newlyRejected());
        assertEquals(0, count("SELECT COUNT(*) FROM market_reject"));
    }

    private CanSlimService service(ScreenerClient screener, Map<String, List<DailyBar>> prices,
                                   List<OpenPosition> held, List<String> universe) {
        return service(screener, pages(Map.of()), prices, held, universe);
    }

    private CanSlimService service(ScreenerClient screener, CompanyFundamentalsClient companyPages,
                                   Map<String, List<DailyBar>> prices, List<OpenPosition> held,
                                   List<String> universe) {
        CandleDataClient candles = (symbol, exchange, segment, start, end, interval) -> {
            pricedOn.add(symbol + "@" + exchange);
            if (refusedSymbols.contains(symbol)) {
                throw new GrowwSymbolRejectedException("Groww candle request for " + symbol
                        + " failed with status 400: GA001 Please provide correct value of trading symbol", 400);
            }
            if (emptySymbols.contains(symbol)) {
                return new StockCandleSeries(symbol, exchange, segment, List.of());
            }
            List<DailyBar> bars = prices.get(symbol);
            if (bars == null) {
                throw new IllegalStateException("unknown symbol " + symbol);
            }
            return new StockCandleSeries(symbol, exchange, segment, bars.stream()
                    .map(b -> new Candle(MARKET.sessionOpenEpoch(b.date()), b.open(), b.high(), b.low(),
                            b.close(), b.volume()))
                    .toList());
        };
        RealizedLotRepository positions = new RealizedLotRepository() {
            @Override
            public void replaceAll(List<RealizedLot> lots, List<OpenPosition> open, String exchange, String segment) {
            }

            @Override
            public List<RealizedLot> findClosedBetween(LocalDate from, LocalDate to) {
                return List.of();
            }

            @Override
            public List<OpenPosition> openPositions() {
                return held;
            }
        };
        return new CanSlimService(screener, companyPages, new SqliteFundamentalsCacheRepository(database),
                new SqliteMarketRejectRepository(database), candles, new SqliteInstrumentRepository(database),
                new SqliteDailyBarRepository(database), new SqliteCanSlimRepository(database), positions,
                MARKET, clock, executor,
                CanSlimCriteria.defaults(),
                new CanSlimService.Settings("NSE", "CASH", "IDX", 400, 150, universe, 7, 2, pageBudget));
    }

    private int count(String sql) {
        return Integer.parseInt(text(sql));
    }

    private String text(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                rs.next();
                return rs.getString(1);
            }
        });
    }
}
