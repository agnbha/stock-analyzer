package com.stockanalyzer.client;

import com.stockanalyzer.model.StockFundamentals;

/**
 * Fetches a live fundamentals/quote snapshot for one instrument. Kept
 * independent of any single provider so a different broker/data API can be
 * substituted without touching callers.
 */
public interface FundamentalsClient {

    StockFundamentals fetchFundamentals(String symbol, String exchange, String segment);
}
