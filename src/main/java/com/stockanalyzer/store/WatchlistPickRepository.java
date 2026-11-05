package com.stockanalyzer.store;

import java.time.LocalDate;
import java.util.List;

/**
 * The Watchlist's shortlists, one per evening, kept so the page can say how they did.
 *
 * <p>Only the choice is stored. What happened next is read from the stored sessions by
 * {@code v_watchlist_outcome}, so a session that is re-ingested corrects its own scorecard.
 */
public interface WatchlistPickRepository {

    /** Picked from the evening's live run. */
    String LIVE = "live";
    /** Rebuilt afterwards from stored history. */
    String REPLAY = "replay";

    /** One place on an evening's shortlist. */
    record Pick(LocalDate asOf, int rank, long instrumentId, String symbol, double score,
                Double close, Double pctAboveSma20, Double ret5, Double rsi14) {
    }

    /**
     * The highest scores among the complete sessions of {@code asOf}, best first, ranks from 1.
     * Empty when no symbol has a score for that date, which is the case until twenty sessions are stored.
     */
    List<Pick> ranking(LocalDate asOf, int limit);

    /** Replaces the whole shortlist of one evening, so a re-run never leaves rows of an older one behind. */
    void replace(LocalDate asOf, List<Pick> picks, String source);

    /** Whether the evening has a shortlist that came from {@code source}. */
    boolean isLogged(LocalDate asOf, String source);

    /** Dates, oldest first, on which at least one symbol has a complete session stored. */
    List<LocalDate> sessionDates(LocalDate from, LocalDate to);
}
