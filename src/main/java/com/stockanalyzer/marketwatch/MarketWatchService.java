package com.stockanalyzer.marketwatch;

import com.stockanalyzer.canslim.DailyBar;
import com.stockanalyzer.store.DailyBarRepository;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.MarketWatchRepository;
import com.stockanalyzer.util.MarketClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keeps what the Market Watch page draws from, and works out what it cannot work out for itself.
 *
 * <ul>
 *   <li><b>The indices' members</b>, from NSE's lists, or the settings' for an index NSE does not publish.</li>
 *   <li><b>Daily bars reaching back {@code historyDays}</b> for every member and every stock of the market. The page
 *       counts 52-week highs and lows, and the market's breadth against 200-day averages, over the last year: a day
 *       needs a year, or 200 sessions, behind it. The nightly CAN SLIM scan reads 400 days of every listed stock, so
 *       what is missing is the span before its first bar: one request a stock, once. A stock listed after that span
 *       starts is noted as asked ({@code daily_bar_reach}) and not asked again. A member whose bars stop short of the
 *       latest session (the scan was not run, or missed it) is read up to today too.</li>
 *   <li><b>Each index's own bars</b> from the broker, two requests an index each run, with their 14-day RSI.</li>
 *   <li><b>The market's breadth</b>, worked out again from the bars: how many stocks closed above and below their 10-,
 *       20-, 50- and 200-day averages, every session.</li>
 * </ul>
 *
 * The 52-week highs and lows themselves are counted by the page from {@code daily_bar}.
 */
public final class MarketWatchService {

    private static final Logger log = LoggerFactory.getLogger(MarketWatchService.class);
    /** Bars that start this close to the span wanted are counted as reaching it: a span rarely starts on a session. */
    static final int REACH_SLACK_DAYS = 14;
    /** Reads that fail in a row before the rest are left for another night: the broker is down or refusing. */
    static final int MAX_FAILURES_IN_A_ROW = 10;
    static final int RSI_PERIOD = 14;

    /** An index's members, as one list. */
    @FunctionalInterface
    public interface MemberSource {
        List<String> members(String index);
    }

    /** A stock's or an index's completed daily bars from {@code from} to {@code to} (null: the latest), oldest first. */
    @FunctionalInterface
    public interface BarSource {
        List<DailyBar> read(String symbol, String exchange, LocalDate from, LocalDate to);
    }

    /**
     * An index the page follows.
     *
     * @param members  its members from the settings; empty for one whose list is read from NSE
     * @param symbol   the index's own symbol at the broker, for its bars and RSI; null for none
     * @param exchange the exchange that symbol is on
     */
    public record Index(String name, List<String> members, String symbol, String exchange) {
    }

    /**
     * @param members how many members the index holds after this run
     * @param source  where they were read from this time, or null when they could not be
     * @param error   why the list could not be read; the members held before are kept
     */
    public record IndexResult(String index, int members, String source, String error) {
    }

    /**
     * @param stocks   the members and the market's stocks, once each
     * @param read     how many were read this time
     * @param failures why each read that failed did; {@code stopped} when the run gave up on the rest
     */
    public record BarsResult(int stocks, int read, List<String> failures, boolean stopped) {
    }

    /** @param error why the index's bars could not be read, or null */
    public record IndexBarsResult(String index, int sessions, LocalDate latest, Double rsi, String error) {
    }

    /**
     * @param bars           null when the run was asked to leave the bars alone
     * @param breadthSessions the sessions the market's breadth was worked out for
     */
    public record Report(List<IndexResult> indices, int dropped, BarsResult bars, List<IndexBarsResult> indexBars,
                         int breadthSessions) {
    }

    private final MemberSource nse;
    private final java.util.function.Function<String, String> nseUrl;
    private final BarSource bars;
    private final MarketWatchRepository repository;
    private final InstrumentRepository instruments;
    private final DailyBarRepository dailyBars;
    private final MarketClock marketClock;
    private final Clock clock;
    private final String exchange;
    private final String segment;
    private final int historyDays;

    /**
     * @param nseUrl      where an index's list is read from, to record with its members
     * @param historyDays how far back each stock's bars should reach: a year of days to count plus the year, or 200
     *                    sessions, before them
     */
    public MarketWatchService(MemberSource nse, java.util.function.Function<String, String> nseUrl, BarSource bars,
                              MarketWatchRepository repository, InstrumentRepository instruments,
                              DailyBarRepository dailyBars, MarketClock marketClock, Clock clock, String exchange,
                              String segment, int historyDays) {
        this.nse = nse;
        this.nseUrl = nseUrl;
        this.bars = bars;
        this.repository = repository;
        this.instruments = instruments;
        this.dailyBars = dailyBars;
        this.marketClock = marketClock;
        this.clock = clock;
        this.exchange = exchange;
        this.segment = segment;
        this.historyDays = historyDays;
    }

    /**
     * Reads each index's members and drops the indices no longer followed; then, with {@code readBars}, the bars the
     * stocks lack and the indices' own bars; then works out the market's breadth from the bars held. An index whose
     * list cannot be read keeps the members it had.
     */
    public Report refresh(List<Index> indices, boolean readBars) {
        long now = clock.instant().getEpochSecond();
        List<IndexResult> results = new ArrayList<>();
        for (int position = 0; position < indices.size(); position++) {
            Index index = indices.get(position);
            try {
                boolean fromSettings = !index.members().isEmpty();
                List<String> symbols = fromSettings ? index.members() : nse.members(index.name());
                String source = fromSettings ? "settings" : nseUrl.apply(index.name());
                repository.replace(index.name(), position, symbols, exchange, source, now);
                results.add(new IndexResult(index.name(), symbols.size(), source, null));
            } catch (RuntimeException e) {
                log.warn("Could not read the members of {}: {}", index.name(), e.getMessage());
                repository.reorder(index.name(), position);
                results.add(new IndexResult(index.name(), repository.counts().getOrDefault(index.name(), 0), null,
                        e.getMessage()));
            }
        }
        int dropped = repository.retainOnly(indices.stream().map(Index::name).toList());
        BarsResult stocks = readBars ? readBars(now) : null;
        List<IndexBarsResult> indexBars = readBars ? readIndexBars(indices, now) : List.of();
        int breadth = repository.rebuildBreadth(segment, now);
        return new Report(results, dropped, stocks, indexBars, breadth);
    }

    private LocalDate from() {
        return LocalDate.now(clock.withZone(marketClock.zone())).minusDays(historyDays);
    }

    private BarsResult readBars(long now) {
        LocalDate from = from();
        LocalDate reachBy = from.plusDays(REACH_SLACK_DAYS);
        Optional<LocalDate> latest = repository.latestSession();
        List<MarketWatchRepository.BarSpan> spans = repository.barSpans(segment);
        List<MarketWatchRepository.BarSpan> due = new ArrayList<>();
        for (MarketWatchRepository.BarSpan span : spans) {
            if (span.first() == null || shortOfReach(span, reachBy) || behind(span, latest)) {
                due.add(span);
            }
        }
        log.info("Market Watch: {} of {} stocks need daily bars read, back to {}", due.size(), spans.size(), from);
        int read = 0;
        int failedInARow = 0;
        List<String> failures = new ArrayList<>();
        for (MarketWatchRepository.BarSpan span : due) {
            if (failedInARow >= MAX_FAILURES_IN_A_ROW) {
                return new BarsResult(spans.size(), read, failures, true);
            }
            try {
                if (span.first() == null) {
                    List<DailyBar> got = bars.read(span.symbol(), span.exchange(), from, null);
                    if (got.isEmpty()) {
                        throw new IllegalStateException("no daily candles returned");
                    }
                    long id = store(span, got);
                    repository.markAsked(id, from, now);
                } else {
                    long id = instruments.findOrCreate(span.symbol(), span.exchange(), segment);
                    if (shortOfReach(span, reachBy)) {
                        // Only the span before the first bar held; nothing there means the stock was not yet listed.
                        List<DailyBar> got = bars.read(span.symbol(), span.exchange(), from, span.first().minusDays(1));
                        if (!got.isEmpty()) {
                            store(span, got);
                        }
                        repository.markAsked(id, from, now);
                    }
                    if (behind(span, latest)) {
                        store(span, bars.read(span.symbol(), span.exchange(), span.last(), null));
                    }
                }
                read++;
                failedInARow = 0;
            } catch (RuntimeException e) {
                log.warn("Could not read {}'s daily bars: {}", span.symbol(), e.getMessage());
                failures.add(span.symbol() + ": " + e.getMessage());
                failedInARow++;
            }
            if ((read + failures.size()) % 250 == 0) {
                log.info("Market Watch: read {} of {}", read + failures.size(), due.size());
            }
        }
        return new BarsResult(spans.size(), read, failures, false);
    }

    private static boolean shortOfReach(MarketWatchRepository.BarSpan span, LocalDate reachBy) {
        return span.first() != null && span.first().isAfter(reachBy)
                && (span.askedFrom() == null || span.askedFrom().isAfter(reachBy));
    }

    /** Only a member is brought up to date here: the market's stocks are the nightly scan's to price. */
    private static boolean behind(MarketWatchRepository.BarSpan span, Optional<LocalDate> latest) {
        return span.member() && span.last() != null && latest.map(day -> span.last().isBefore(day)).orElse(false);
    }

    private long store(MarketWatchRepository.BarSpan span, List<DailyBar> got) {
        long id = instruments.findOrCreate(span.symbol(), span.exchange(), segment);
        if (!got.isEmpty()) {
            dailyBars.upsert(id, got, bar -> marketClock.sessionOpenEpoch(bar.date()));
        }
        return id;
    }

    private List<IndexBarsResult> readIndexBars(List<Index> indices, long now) {
        List<IndexBarsResult> results = new ArrayList<>();
        for (Index index : indices) {
            if (index.symbol() == null) {
                continue;
            }
            try {
                List<DailyBar> got = bars.read(index.symbol(), index.exchange(), from(), null);
                if (got.isEmpty()) {
                    throw new IllegalStateException("no daily candles returned for " + index.exchange() + ":"
                            + index.symbol());
                }
                List<Double> rsi = Rsi.wilder(got.stream().map(DailyBar::close).toList(), RSI_PERIOD);
                List<MarketWatchRepository.IndexBar> rows = new ArrayList<>();
                for (int i = 0; i < got.size(); i++) {
                    DailyBar bar = got.get(i);
                    rows.add(new MarketWatchRepository.IndexBar(bar.date(), marketClock.sessionOpenEpoch(bar.date()),
                            bar.open(), bar.high(), bar.low(), bar.close(), rsi.get(i)));
                }
                repository.upsertIndexBars(index.name(), rows, now);
                results.add(new IndexBarsResult(index.name(), rows.size(), rows.getLast().date(),
                        rows.getLast().rsi14(), null));
            } catch (RuntimeException e) {
                log.warn("Could not read {}'s own bars: {}", index.name(), e.getMessage());
                results.add(new IndexBarsResult(index.name(), 0, null, null, e.getMessage()));
            }
        }
        return results;
    }
}
