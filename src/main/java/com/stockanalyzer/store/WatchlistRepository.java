package com.stockanalyzer.store;

import java.time.LocalDate;
import java.util.Set;

/** The symbol sets an alert scope is built from. */
public interface WatchlistRepository {

    /**
     * Symbols reading bullish at the last session before {@code before}: close and
     * the 5-day average both above the 20-day average, the same test the Watchlist
     * dashboard counts. Sessions on or after {@code before} are ignored, so the set
     * does not shift when the daemon restarts after today's partial session is stored.
     */
    Set<String> bullishSymbols(LocalDate before);

    /**
     * Symbols held long at the broker or open long in the trade journal. A negative journal
     * quantity is a sell with no matching buy in the imported history, not a position.
     */
    Set<String> heldSymbols();
}
