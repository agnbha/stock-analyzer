package com.stockanalyzer.client;

import com.stockanalyzer.model.StockCandleSeries;

import java.time.LocalDateTime;

/** Decorator that spends a rate-limit token before each fetch. */
public final class RateLimitedCandleDataClient implements CandleDataClient {

    private final CandleDataClient delegate;
    private final RateLimiter rateLimiter;

    public RateLimitedCandleDataClient(CandleDataClient delegate, RateLimiter rateLimiter) {
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                           LocalDateTime startTime, LocalDateTime endTime, int intervalMinutes) {
        rateLimiter.acquire();
        return delegate.fetchCandles(symbol, exchange, segment, startTime, endTime, intervalMinutes);
    }
}
