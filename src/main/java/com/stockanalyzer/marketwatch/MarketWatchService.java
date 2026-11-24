package com.stockanalyzer.marketwatch;

import com.stockanalyzer.canslim.DailyBar;
import com.stockanalyzer.store.DailyBarRepository;
import com.stockanalyzer.store.IndexMemberRepository;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.util.MarketClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keeps what the Market Watch page counts its 52-week highs and lows from: the members of each index it follows, and
 * enough of each member's daily bars that every day on the page has a whole year behind it.
 *
 * <p>The page itself does the counting, from {@code daily_bar}: a stock makes a new 52-week high on a day when its high
 * is above every high of the 52 weeks before, and a new low when its low is below every low; net new highs are the
 * highs less the lows. The nightly CAN SLIM scan already reads 400 days of bars for every listed stock, which leaves the
 * first year of them with no year behind it. So a member whose bars do not reach back {@code historyDays} is read again
 * over that span (two requests a stock, as the daily client asks for 400 days at a time); so is one whose bars stop short of the latest session (the scan was not run, or
 * missed it). A stock listed more recently than that is read every night, which costs a request and is harmless.
 */
public final class MarketWatchService {

    private static final Logger log = LoggerFactory.getLogger(MarketWatchService.class);
    /** Bars that start this close to the span wanted are counted as reaching it: a span rarely starts on a session. */
    static final int REACH_SLACK_DAYS = 14;
    /** Reads that fail in a row before the rest are left for another night: the broker is down or refusing. */
    static final int MAX_FAILURES_IN_A_ROW = 10;

    /** An index's members, as one list. */
    @FunctionalInterface
    public interface MemberSource {
        List<String> members(String index);
    }

    /** A stock's completed daily bars from {@code from} to the latest, oldest first. */
    @FunctionalInterface
    public interface BarSource {
        List<DailyBar> read(String symbol, String exchange, LocalDate from);
    }

    /**
     * An index the page follows.
     *
     * @param members its members from the settings; empty for one whose list is read from NSE
     */
    public record Index(String name, List<String> members) {
    }

    /**
     * @param members how many members the index holds after this run
     * @param source  where they were read from this time, or null when they could not be
     * @param error   why the list could not be read; the members held before are kept
     */
    public record IndexResult(String index, int members, String source, String error) {
    }

    /**
     * @param stocks   the members, once each, across every index
     * @param read     how many were read this time
     * @param failures why each read that failed did; {@code stopped} when the run gave up on the rest
     */
    public record BarsResult(int stocks, int read, List<String> failures, boolean stopped) {
    }

    /** @param bars null when the run was asked to leave the bars alone */
    public record Report(List<IndexResult> indices, int dropped, BarsResult bars) {
    }

    private final MemberSource nse;
    private final java.util.function.Function<String, String> nseUrl;
    private final BarSource bars;
    private final IndexMemberRepository members;
    private final InstrumentRepository instruments;
    private final DailyBarRepository dailyBars;
    private final MarketClock marketClock;
    private final Clock clock;
    private final String exchange;
    private final String segment;
    private final int historyDays;

    /**
     * @param nseUrl      where an index's list is read from, to record with its members
     * @param historyDays how far back each member's bars should reach: a year of days to count plus the year before them
     */
    public MarketWatchService(MemberSource nse, java.util.function.Function<String, String> nseUrl, BarSource bars,
                              IndexMemberRepository members, InstrumentRepository instruments,
                              DailyBarRepository dailyBars, MarketClock marketClock, Clock clock, String exchange,
                              String segment, int historyDays) {
        this.nse = nse;
        this.nseUrl = nseUrl;
        this.bars = bars;
        this.members = members;
        this.instruments = instruments;
        this.dailyBars = dailyBars;
        this.marketClock = marketClock;
        this.clock = clock;
        this.exchange = exchange;
        this.segment = segment;
        this.historyDays = historyDays;
    }

    /**
     * Reads each index's members, drops the indices no longer followed, then (with {@code readBars}) reads the bars the
     * members lack. An index whose list cannot be read keeps the members it had.
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
                members.replace(index.name(), position, symbols, exchange, source, now);
                results.add(new IndexResult(index.name(), symbols.size(), source, null));
            } catch (RuntimeException e) {
                log.warn("Could not read the members of {}: {}", index.name(), e.getMessage());
                members.reorder(index.name(), position);
                results.add(new IndexResult(index.name(), members.counts().getOrDefault(index.name(), 0), null,
                        e.getMessage()));
            }
        }
        int dropped = members.retainOnly(indices.stream().map(Index::name).toList());
        return new Report(results, dropped, readBars ? readBars() : null);
    }

    private BarsResult readBars() {
        LocalDate from = LocalDate.now(clock.withZone(marketClock.zone())).minusDays(historyDays);
        Optional<LocalDate> latest = members.latestSession();
        List<IndexMemberRepository.BarSpan> spans = members.barSpans(segment);
        List<IndexMemberRepository.BarSpan> due = spans.stream().filter(span -> span.first() == null
                || span.first().isAfter(from.plusDays(REACH_SLACK_DAYS))
                || latest.map(day -> span.last().isBefore(day)).orElse(false)).toList();
        log.info("Market Watch: {} of {} members need their daily bars read from {}", due.size(), spans.size(), from);
        int read = 0;
        int failedInARow = 0;
        List<String> failures = new ArrayList<>();
        for (IndexMemberRepository.BarSpan span : due) {
            if (failedInARow >= MAX_FAILURES_IN_A_ROW) {
                return new BarsResult(spans.size(), read, failures, true);
            }
            try {
                List<DailyBar> got = bars.read(span.symbol(), span.exchange(), from);
                if (got.isEmpty()) {
                    throw new IllegalStateException("no daily candles returned");
                }
                long instrumentId = instruments.findOrCreate(span.symbol(), span.exchange(), segment);
                dailyBars.upsert(instrumentId, got, bar -> marketClock.sessionOpenEpoch(bar.date()));
                read++;
                failedInARow = 0;
            } catch (RuntimeException e) {
                log.warn("Could not read {}'s daily bars: {}", span.symbol(), e.getMessage());
                failures.add(span.symbol() + ": " + e.getMessage());
                failedInARow++;
            }
            if ((read + failures.size()) % 100 == 0) {
                log.info("Market Watch: read {} of {}", read + failures.size(), due.size());
            }
        }
        return new BarsResult(spans.size(), read, failures, false);
    }
}
