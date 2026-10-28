package com.stockanalyzer.canslim;

import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.OpenPosition;
import com.stockanalyzer.store.CanSlimRepository;
import com.stockanalyzer.store.DailyBarRepository;
import com.stockanalyzer.store.FundamentalsCacheRepository;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.RealizedLotRepository;
import com.stockanalyzer.util.MarketClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * One CAN SLIM scan, end to end:
 * <ol>
 *   <li>run the screener query - the fundamental filter (C, A, I);</li>
 *   <li>add open positions and the configured universe, so sell signals cover
 *       what is actually held and the RS ranking has a wider field;</li>
 *   <li>fetch about a year of daily bars for all of them, plus the market proxy;</li>
 *   <li>read the market's direction (M), rank relative strength (L), analyse
 *       each stock's price action (N, S, bases, breakouts) and decide signals;</li>
 *   <li>store the bars and the scores, so the dashboards can show both.</li>
 * </ol>
 * Daily bars cost one request per symbol: the daily interval allows years per
 * request, unlike the intraday one.
 */
public final class CanSlimService {

    private static final Logger log = LoggerFactory.getLogger(CanSlimService.class);
    private static final int DAILY_INTERVAL_MINUTES = 1440;
    private static final DateTimeFormatter QUARTER_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    /**
     * @param historyDays   calendar days of daily bars to fetch: a year of sessions plus room for the 200-day average
     * @param maxCandidates the most screened companies to price in one scan
     * @param universe      extra symbols to price for RS ranking and sell signals
     * @param fundamentalsCacheDays the slow pace for company pages with nothing new to learn from: ones with
     *                      no figures, and companies past the reporting deadline still missing the quarter;
     *                      0 reads every page every scan
     * @param fundamentalsSeasonCheckDays how often, in reporting season, to look again at a company that has
     *                      not yet shown the newly ended quarter
     * @param maxPagesPerRun the most company pages one scan reads, across holdings, the universe and the
     *                      market's leaders. screener.in admits about one page every ten seconds, so a first
     *                      scan of hundreds is spread over several nights, strongest RS first, rather than
     *                      held up for an hour
     */
    public record Settings(String exchange, String segment, String indexSymbol, int historyDays,
                           int maxCandidates, List<String> universe, int fundamentalsCacheDays,
                           int fundamentalsSeasonCheckDays, int maxPagesPerRun) {
    }

    private final ScreenerClient screener;
    private final CompanyFundamentalsClient companyFundamentals;
    private final FundamentalsCacheRepository fundamentalsCache;
    private final FundamentalsRefreshPolicy refreshPolicy;
    private final CandleDataClient dailyClient;
    private final InstrumentRepository instruments;
    private final DailyBarRepository dailyBars;
    private final CanSlimRepository runs;
    private final RealizedLotRepository positions;
    private final MarketClock marketClock;
    private final Clock clock;
    private final ExecutorService executor;
    private final Settings settings;
    private final MarketDirectionAnalyzer marketAnalyzer;
    private final PriceActionAnalyzer priceActionAnalyzer;
    private final CanSlimEvaluator evaluator;

    public CanSlimService(ScreenerClient screener, CompanyFundamentalsClient companyFundamentals,
                          FundamentalsCacheRepository fundamentalsCache, CandleDataClient dailyClient,
                          InstrumentRepository instruments, DailyBarRepository dailyBars, CanSlimRepository runs,
                          RealizedLotRepository positions, MarketClock marketClock, Clock clock,
                          ExecutorService executor, CanSlimCriteria criteria, Settings settings) {
        this.screener = screener;
        this.companyFundamentals = companyFundamentals;
        this.fundamentalsCache = fundamentalsCache;
        this.refreshPolicy = new FundamentalsRefreshPolicy(settings.fundamentalsCacheDays(),
                settings.fundamentalsSeasonCheckDays());
        this.dailyClient = dailyClient;
        this.instruments = instruments;
        this.dailyBars = dailyBars;
        this.runs = runs;
        this.positions = positions;
        this.marketClock = marketClock;
        this.clock = clock;
        this.executor = executor;
        this.settings = settings;
        this.marketAnalyzer = new MarketDirectionAnalyzer(criteria);
        this.priceActionAnalyzer = new PriceActionAnalyzer(criteria);
        this.evaluator = new CanSlimEvaluator(criteria);
    }

    /**
     * @param query the screener query; null skips screening, which leaves only
     *              holdings and the universe - sell signals, and buy signals for
     *              any of them whose own fundamentals qualify
     */
    public CanSlimReport scan(String query) {
        return scan(query, null);
    }

    /**
     * @param wide every listed symbol to price and rank as well, or null for the usual scan. Those
     *             symbols are ranked together with the rest, so the RS rating becomes a percentile of
     *             the market rather than of a hand-picked field; their fundamentals are looked up only
     *             once they rank high enough to matter. Market symbols are never given sell signals:
     *             nobody holds them.
     */
    public CanSlimReport scan(String query, MarketScan wide) {
        Map<String, ScreenedStock> screened = new LinkedHashMap<>();
        int screenedCount = 0;
        String screenerNotice = null;
        boolean screenerFailed = false;
        boolean needsLogin = false;
        if (query != null) {
            if (!screener.hasSession()) {
                screenerNotice = "No screener.in session (SCREENER_SESSION_ID), so the query was not run: "
                        + "no new candidates were screened. Holdings and the configured symbols were scored "
                        + "from their public screener.in company pages.";
            } else {
                try {
                    ScreenerClient.Result found = screener.screen(query);
                    List<ScreenedStock> results = found.stocks();
                    screenedCount = results.size();
                    if (found.moreAvailable()) {
                        screenerNotice = "The query matched more than one page of results; only the first page ("
                                + results.size() + " companies) was read, because screener.in's robots.txt asks "
                                + "automated clients not to page. Tighten canslim.screener.query so it fits on one page.";
                    }
                    results.stream().limit(settings.maxCandidates()).forEach(s -> screened.put(s.symbol(), s));
                    if (results.size() > settings.maxCandidates()) {
                        log.warn("The query returned {} companies; scoring the first {}. Tighten the query or "
                                + "raise canslim.max.candidates.", results.size(), settings.maxCandidates());
                    }
                } catch (ScreenerException e) {
                    // The rest of the scan does not depend on the query, and holdings' sell signals must not
                    // wait on a login: record why, carry on, and let the caller decide how loudly to fail.
                    screenerNotice = e.getMessage();
                    screenerFailed = true;
                    needsLogin = e.needsLogin();
                    log.warn("The screener query did not run: {}", e.getMessage());
                }
            }
        }

        Map<String, Double> holdingCosts = holdingCosts();
        Map<String, CanSlimScore.Source> sources = new LinkedHashMap<>();
        screened.keySet().forEach(symbol -> sources.put(symbol, CanSlimScore.Source.SCREENER));
        holdingCosts.keySet().forEach(symbol -> sources.putIfAbsent(symbol, CanSlimScore.Source.HOLDING));
        settings.universe().forEach(symbol -> sources.putIfAbsent(symbol, CanSlimScore.Source.UNIVERSE));
        Map<String, String> exchangeOf = new HashMap<>();
        Map<String, String> pageKeyOf = new HashMap<>();
        if (wide != null) {
            for (MarketListing listing : wide.listings()) {
                exchangeOf.put(listing.symbol(), listing.exchange());
                pageKeyOf.put(listing.symbol(), listing.screenerKey());
                sources.putIfAbsent(listing.symbol(), CanSlimScore.Source.MARKET);
            }
        }

        // The query's own columns carry C, A and I for what it returned. Everything else is read
        // from the company pages, so those letters are known rather than blank.
        List<String> needFundamentals = sources.keySet().stream()
                .filter(symbol -> !screened.containsKey(symbol) && !symbol.equals(settings.indexSymbol())
                        && sources.get(symbol) != CanSlimScore.Source.MARKET)
                .toList();
        java.util.concurrent.atomic.AtomicInteger pageBudget =
                new java.util.concurrent.atomic.AtomicInteger(settings.maxPagesPerRun());
        FundamentalsResult pageFundamentals = pageFundamentals(needFundamentals, exchangeOf, pageKeyOf, pageBudget);

        List<String> toPrice = new ArrayList<>(sources.keySet());
        if (!sources.containsKey(settings.indexSymbol())) {
            toPrice.add(settings.indexSymbol());
        }
        log.info("Pricing {} symbols: {} screened, {} held, plus the universe, {} market listings and {}",
                toPrice.size(), screened.size(), holdingCosts.size(),
                wide == null ? 0 : wide.listings().size(), settings.indexSymbol());
        Map<String, Priced> priced = priceAll(toPrice, exchangeOf);

        List<String> failures = new ArrayList<>();
        int marketFailed = 0;
        for (Map.Entry<String, Priced> entry : priced.entrySet()) {
            if (entry.getValue().error() == null) {
                continue;
            }
            if (sources.get(entry.getKey()) == CanSlimScore.Source.MARKET) {
                // Thousands of listings: a handful of lines show the pattern, the count carries the rest.
                if (++marketFailed <= MAX_LISTED_MARKET_FAILURES) {
                    failures.add(entry.getKey() + ": " + entry.getValue().error());
                }
            } else {
                failures.add(entry.getKey() + ": " + entry.getValue().error());
            }
        }
        if (marketFailed > MAX_LISTED_MARKET_FAILURES) {
            failures.add("... and " + (marketFailed - MAX_LISTED_MARKET_FAILURES) + " more market symbols");
        }

        Priced index = priced.get(settings.indexSymbol());
        MarketAssessment market = index == null || index.bars().isEmpty()
                ? MarketAssessment.unknown(settings.indexSymbol(), "no prices for the market proxy "
                + settings.indexSymbol() + (index == null || index.error() == null ? "" : ": " + index.error()))
                : marketAnalyzer.assess(settings.indexSymbol(), index.bars());

        LocalDate today = LocalDate.now(clock.withZone(marketClock.zone()));
        Map<String, PriceAction> actions = new HashMap<>();
        Map<String, Double> weightedReturns = new HashMap<>();
        Map<String, String> unranked = new LinkedHashMap<>();
        for (String symbol : sources.keySet()) {
            Priced p = priced.get(symbol);
            PriceAction action = p == null ? null : p.action();
            boolean listed = sources.get(symbol) == CanSlimScore.Source.MARKET;
            if (listed) {
                String reason = action == null ? "no prices"
                        : wide.unrankedReason(p.sessions(), action.close(), p.avgTurnover(), p.lastBar(), today);
                if (reason != null) {
                    unranked.put(symbol, reason);
                    continue;
                }
            }
            if (action != null) {
                actions.put(symbol, action);
                weightedReturns.put(symbol, action.weightedReturn());
            }
        }
        Map<String, Integer> rsRatings = RelativeStrength.ratings(weightedReturns);

        // The stocks that rank high enough to be a buy are the only market-wide ones whose earnings
        // are worth a page load; the rest cannot pass L, whatever their numbers say.
        FundamentalsResult leaderFundamentals = new FundamentalsResult(Map.of(), null);
        List<String> leaders = List.of();
        if (wide != null) {
            leaders = sources.entrySet().stream()
                    .filter(e -> e.getValue() == CanSlimScore.Source.MARKET && actions.containsKey(e.getKey()))
                    .map(Map.Entry::getKey)
                    .filter(symbol -> rsRatings.get(symbol) != null
                            && rsRatings.get(symbol) >= wide.fundamentalsMinRs())
                    // The page budget runs out before the list does on a first scan: spend it on the strongest.
                    .sorted(Comparator.comparing((String symbol) -> -rsRatings.get(symbol)).thenComparing(symbol -> symbol))
                    .toList();
            leaderFundamentals = pageFundamentals(leaders, exchangeOf, pageKeyOf, pageBudget);
        }

        Map<Long, CanSlimScore> scores = new LinkedHashMap<>();
        for (Map.Entry<String, CanSlimScore.Source> entry : sources.entrySet()) {
            String symbol = entry.getKey();
            if (unranked.containsKey(symbol)) {
                continue;
            }
            ScreenedStock fundamentals = screened.containsKey(symbol) ? screened.get(symbol)
                    : pageFundamentals.stocks().containsKey(symbol) ? pageFundamentals.stocks().get(symbol)
                    : leaderFundamentals.stocks().get(symbol);
            CanSlimScore score = evaluator.evaluate(symbol, entry.getValue(), fundamentals,
                    actions.get(symbol), rsRatings.get(symbol), market, holdingCosts.get(symbol));
            scores.put(instruments.findOrCreate(symbol, exchangeOf.getOrDefault(symbol, settings.exchange()),
                    settings.segment()), score);
        }

        long runId = runs.saveRun(clock.instant().getEpochSecond(), query, screenedCount, market, scores);
        MarketSummary summary = null;
        if (wide != null) {
            runs.pruneMarketScores(wide.keepRuns());
            Map<String, Integer> byReason = new java.util.TreeMap<>();
            unranked.values().stream().filter(reason -> !reason.equals("no prices"))
                    .forEach(reason -> byReason.merge(reason, 1, Integer::sum));
            long ranked = scores.values().stream().filter(sc -> sc.source() == CanSlimScore.Source.MARKET).count();
            summary = new MarketSummary(wide.listings().size(), marketFailed, (int) ranked, byReason,
                    leaders.size());
        }
        List<CanSlimScore> ordered = scores.values().stream().sorted(REPORT_ORDER).toList();
        String fundamentalsNotice = pageFundamentals.notice();
        if (leaderFundamentals.notice() != null) {
            String leaderNotice = "Market leaders (RS " + wide.fundamentalsMinRs() + "+): "
                    + leaderFundamentals.notice();
            fundamentalsNotice = fundamentalsNotice == null ? leaderNotice : fundamentalsNotice + "\n" + leaderNotice;
        }
        return new CanSlimReport(runId, query, screenedCount, market, ordered, failures, screenerNotice,
                screenerFailed, needsLogin, fundamentalsNotice, summary);
    }

    private record FundamentalsResult(Map<String, ScreenedStock> stocks, String notice) {
    }

    /**
     * C, A and I for symbols the query did not return, from their public company
     * pages. A page is read again only when the calendar says the company may
     * have reported since ({@link FundamentalsRefreshPolicy}), so most nights read
     * nothing and reporting season reads exactly the companies still to report. A
     * failed fetch leaves that stock's letters unknown; a rate limit stops the
     * fetching altogether rather than asking again, and the rest is picked up on a
     * later run.
     */
    private FundamentalsResult pageFundamentals(List<String> symbols, Map<String, String> exchangeOf,
                                               Map<String, String> pageKeyOf,
                                               java.util.concurrent.atomic.AtomicInteger pageBudget) {
        Map<String, ScreenedStock> stocks = new LinkedHashMap<>();
        if (symbols.isEmpty()) {
            return new FundamentalsResult(stocks, null);
        }
        long now = clock.instant().getEpochSecond();
        LocalDate today = LocalDate.now(clock.withZone(marketClock.zone()));
        int reused = 0;
        int fetched = 0;
        int unavailable = 0;
        int awaiting = 0;
        String stopped = null;
        for (String symbol : symbols) {
            long instrumentId = instruments.findOrCreate(symbol, exchangeOf.getOrDefault(symbol, settings.exchange()),
                    settings.segment());
            Optional<FundamentalsCacheRepository.Cached> hit = fundamentalsCache.find(instrumentId);
            CompanyFundamentals fundamentals;
            if (hit.isPresent() && !refreshPolicy.due(hit.get(), today, now)) {
                fundamentals = hit.get().fundamentals();
                reused++;
            } else if (stopped != null) {
                fundamentals = hit.map(FundamentalsCacheRepository.Cached::fundamentals)
                        .orElse(CompanyFundamentals.NONE);
            } else if (pageBudget.getAndDecrement() <= 0) {
                stopped = "the " + settings.maxPagesPerRun() + "-page budget for one scan is spent; the rest "
                        + "follow on later runs";
                fundamentals = hit.map(FundamentalsCacheRepository.Cached::fundamentals)
                        .orElse(CompanyFundamentals.NONE);
            } else {
                try {
                    fundamentals = companyFundamentals.fetch(pageKeyOf.getOrDefault(symbol, symbol))
                            .orElse(CompanyFundamentals.NONE);
                    fundamentalsCache.upsert(instrumentId, now, fundamentals);
                    fetched++;
                } catch (ScreenerException e) {
                    log.warn("Could not read fundamentals for {}: {}", symbol, e.getMessage());
                    stopped = e.getMessage();
                    fundamentals = hit.map(FundamentalsCacheRepository.Cached::fundamentals)
                            .orElse(CompanyFundamentals.NONE);
                }
            }
            if (fundamentals.isEmpty()) {
                unavailable++;
            } else {
                stocks.put(symbol, new ScreenedStock(symbol, symbol, fundamentals.ratios()));
                if (FundamentalsRefreshPolicy.awaitingResults(fundamentals, today)) {
                    awaiting++;
                }
            }
        }
        YearMonth expected = FundamentalsRefreshPolicy.expectedQuarter(today);
        String notice = String.format(Locale.ROOT, "Fundamentals for %d symbols from public screener.in company "
                        + "pages: %d fetched, %d reused, %d with none available; %d still showing an earlier "
                        + "quarter than %s%s",
                symbols.size(), fetched, reused, unavailable, awaiting,
                expected.format(QUARTER_LABEL), stopped == null ? "" : "; stopped early: " + stopped);
        log.info(notice);
        return new FundamentalsResult(stocks, notice);
    }

    /** Sells first, then buys, then setups; within a signal the strongest by letters and RS. */
    private static final Comparator<CanSlimScore> REPORT_ORDER = Comparator
            .comparing((CanSlimScore s) -> s.signal().ordinal())
            .thenComparing(s -> -s.letters().passed())
            .thenComparing(s -> s.rsRating() == null ? 0 : -s.rsRating())
            .thenComparing(CanSlimScore::symbol);

    /**
     * One priced symbol. The bars are kept only for the market proxy, whose chart the market's direction
     * is read from; everything else is analysed as it arrives and let go, so a scan of thousands of
     * symbols does not hold a year of bars for each of them.
     */
    private record Priced(List<DailyBar> bars, PriceAction action, int sessions, double avgTurnover,
                          LocalDate lastBar, String error) {

        static Priced failed(String error) {
            return new Priced(List.of(), null, 0, 0, null, error);
        }
    }

    private static final int TURNOVER_WINDOW = 50;
    private static final int MAX_LISTED_MARKET_FAILURES = 20;
    private static final int PROGRESS_EVERY = 250;

    private Map<String, Priced> priceAll(List<String> symbols, Map<String, String> exchangeOf) {
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, CompletableFuture<Priced>> futures = new LinkedHashMap<>();
        for (String symbol : symbols) {
            futures.put(symbol, CompletableFuture.supplyAsync(() -> {
                Priced priced = price(symbol, exchangeOf.getOrDefault(symbol, settings.exchange()));
                int n = done.incrementAndGet();
                if (n % PROGRESS_EVERY == 0) {
                    log.info("Priced {} of {} symbols", n, symbols.size());
                }
                return priced;
            }, executor));
        }
        Map<String, Priced> priced = new LinkedHashMap<>();
        futures.forEach((symbol, future) -> priced.put(symbol, future.join()));
        return priced;
    }

    private Priced price(String symbol, String exchange) {
        try {
            LocalDateTime end = LocalDateTime.now(clock.withZone(marketClock.zone()));
            List<Candle> candles = dailyClient.fetchCandles(symbol, exchange, settings.segment(),
                    end.minusDays(settings.historyDays()), end, DAILY_INTERVAL_MINUTES).candles();
            List<DailyBar> bars = completedBars(candles);
            if (bars.isEmpty()) {
                return Priced.failed("no daily candles returned - check the symbol");
            }
            long instrumentId = instruments.findOrCreate(symbol, exchange, settings.segment());
            dailyBars.upsert(instrumentId, bars, bar -> marketClock.sessionOpenEpoch(bar.date()));
            double turnover = bars.subList(Math.max(0, bars.size() - TURNOVER_WINDOW), bars.size()).stream()
                    .mapToDouble(bar -> bar.close() * bar.volume()).average().orElse(0);
            return new Priced(symbol.equals(settings.indexSymbol()) ? bars : List.of(),
                    priceActionAnalyzer.analyze(bars), bars.size(), turnover, bars.getLast().date(), null);
        } catch (RuntimeException e) {
            log.warn("Could not price {}: {}", symbol, e.getMessage());
            return Priced.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * One bar per session, oldest first, without today's while the market is
     * open: a half-formed day would read as a volume collapse.
     */
    private List<DailyBar> completedBars(List<Candle> candles) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(marketClock.zone()));
        LocalDate today = now.toLocalDate();
        boolean sessionOver = !now.toLocalTime().isBefore(marketClock.sessionClose());

        TreeMap<LocalDate, DailyBar> byDate = new TreeMap<>();
        for (Candle candle : candles) {
            LocalDate date = marketClock.sessionDateOf(candle.epochSeconds());
            if (date.equals(today) && !sessionOver) {
                continue;
            }
            byDate.put(date, new DailyBar(date, candle.open(), candle.high(), candle.low(), candle.close(),
                    candle.volume()));
        }
        return new ArrayList<>(byDate.values());
    }

    /** Average cost per held symbol, weighted by quantity across products. */
    private Map<String, Double> holdingCosts() {
        Map<String, double[]> totals = new LinkedHashMap<>();
        for (OpenPosition position : positions.openPositions()) {
            if (position.quantity() <= 0) {
                continue;
            }
            double[] t = totals.computeIfAbsent(position.symbol(), k -> new double[2]);
            t[0] += position.avgCost() * position.quantity();
            t[1] += position.quantity();
        }
        Map<String, Double> costs = new LinkedHashMap<>();
        totals.forEach((symbol, t) -> costs.put(symbol, t[0] / t[1]));
        return costs;
    }
}
