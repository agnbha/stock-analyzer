package com.stockanalyzer.client;

import com.stockanalyzer.model.StockCandleSeries;

import java.time.LocalDateTime;

/**
 * Fetches historical OHLCV candles for one instrument. Kept independent of
 * any single provider so a different broker/data API can be substituted
 * without touching {@link com.stockanalyzer.service.StockGrowthAnalysisService}.
 */
public interface CandleDataClient {

    StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                    LocalDateTime startTime, LocalDateTime endTime, int intervalMinutes);
}
