package com.stockanalyzer.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What the Market Watch page draws from; see {@code CanSlimMain marketwatch}. */
public interface MarketWatchRepository {

    /** The moving averages the market's breadth is counted against, in sessions. */
    List<Integer> BREADTH_AVERAGES = List.of(10, 20, 50, 200);

    /** Replaces an index's members, read from {@code source}, and puts the index at {@code position} in the page's order. */
    void replace(String index, int position, List<String> symbols, String exchange, String source, long nowEpoch);

    /** Puts an index at {@code position} in the page's order, keeping the members it holds. */
    void reorder(String index, int position);

    /** Drops the indices not named. Returns how many were dropped. */
    int retainOnly(Collection<String> indices);

    /** How many members each index holds, by index, in the page's order. */
    Map<String, Integer> counts();

    /**
     * The stocks whose bars the page needs: every member of an index, and every stock of the market with a bar in the
     * ten days to the latest session. Each once, members first, with the first and last session it has a bar for (null
     * when none) and how far back its bars have been asked for.
     */
    List<BarSpan> barSpans(String segment);

    /** Notes that a stock's bars have been asked for back to {@code from}. */
    void markAsked(long instrumentId, LocalDate from, long nowEpoch);

    /** The latest session any stock has a daily bar for. */
    Optional<LocalDate> latestSession();

    /**
     * Works out the market's breadth again for every session from the daily bars: for each of {@link #BREADTH_AVERAGES},
     * how many stocks closed above and below their average of that many sessions (their own sessions, today's close
     * included), out of those with that many sessions of bars. Returns the sessions written.
     */
    int rebuildBreadth(String segment, long nowEpoch);

    /** Writes an index's own daily bars, replacing those held for the same sessions. */
    void upsertIndexBars(String index, List<IndexBar> bars, long nowEpoch);

    /**
     * @param member    in one of the indices followed
     * @param askedFrom how far back its bars have been asked for, or null
     */
    record BarSpan(String symbol, String exchange, boolean member, LocalDate first, LocalDate last, LocalDate askedFrom) {
    }

    /** One session of an index, with its 14-day RSI (null until there are 14 changes to average). */
    record IndexBar(LocalDate date, long tsEpoch, double open, double high, double low, double close, Double rsi14) {
    }
}
