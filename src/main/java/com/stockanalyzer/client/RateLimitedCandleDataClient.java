package com.stockanalyzer.client;

import com.stockanalyzer.model.StockCandleSeries;

import java.time.LocalDateTime;

/**
 * Spends a rate-limit token before each fetch, and feeds the provider's own
 * throttling back into the limiter.
 *
 * <p>When the server returns 429, every thread is held back - not just the one
 * that was refused. The penalty is the server's {@code Retry-After} when it
 * sends one, and a short default when it does not.
 */
public final class RateLimitedCandleDataClient implements CandleDataClient {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final long DEFAULT_THROTTLE_PENALTY_MILLIS = 2_000;

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
        try {
            return delegate.fetchCandles(symbol, exchange, segment, startTime, endTime, intervalMinutes);
        } catch (GrowwApiException e) {
            if (e.statusCode() == TOO_MANY_REQUESTS) {
                rateLimiter.penalise(e.retryAfterMillis() > 0
                        ? e.retryAfterMillis()
                        : DEFAULT_THROTTLE_PENALTY_MILLIS);
            }
            throw e;
        }
    }
}
