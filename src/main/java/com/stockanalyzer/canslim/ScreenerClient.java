package com.stockanalyzer.canslim;

import java.util.List;

/**
 * Runs a fundamentals query and returns the companies that pass it.
 *
 * <p>The query does the coarse fundamental filtering (the C, A and I of
 * CAN SLIM) on the provider's side, where the quarterly and annual numbers
 * live; everything price-based is evaluated locally from candles.
 */
public interface ScreenerClient {

    /**
     * @param moreAvailable the query matched more than the one page that was read. Paging is not an
     *                      option - screener.in's robots.txt asks automated clients to stay off the
     *                      {@code page} and {@code limit} parameters - so the answer is a tighter query
     */
    record Result(List<ScreenedStock> stocks, boolean moreAvailable) {
    }

    Result screen(String query);

    /** Whether a query can be attempted at all. False means the account's session was never configured. */
    default boolean hasSession() {
        return true;
    }
}
