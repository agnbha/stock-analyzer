package com.stockanalyzer.canslim;

import java.util.Optional;

/**
 * Fundamentals for one company by symbol - the source CAN SLIM falls back on
 * when no screener query ran. Empty means the source has nothing on the symbol
 * (an ETF, a symbol it does not list), as opposed to an exception, which means
 * it could not be asked.
 */
public interface CompanyFundamentalsClient {

    Optional<CompanyFundamentals> fetch(String symbol);

    /**
     * The same page read for what to show next to a chart rather than for what CAN SLIM scores. Empty when the
     * source has nothing on the symbol; a source that cannot give a profile says so by always being empty.
     */
    default Optional<CompanyProfile> fetchProfile(String symbol) {
        return Optional.empty();
    }
}
