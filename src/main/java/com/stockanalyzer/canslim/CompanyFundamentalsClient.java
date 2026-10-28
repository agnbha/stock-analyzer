package com.stockanalyzer.canslim;

import java.util.Map;
import java.util.Optional;

/**
 * Fundamentals for one company by symbol - the source CAN SLIM falls back on
 * when no screener query ran. Empty means the source has nothing on the symbol
 * (an ETF, a symbol it does not list), as opposed to an exception, which means
 * it could not be asked.
 */
public interface CompanyFundamentalsClient {

    Optional<Map<String, Double>> fetch(String symbol);
}
