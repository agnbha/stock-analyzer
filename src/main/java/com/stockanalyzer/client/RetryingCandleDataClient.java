package com.stockanalyzer.client;

import com.stockanalyzer.model.StockCandleSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decorator retrying rate-limit and server-side failures with exponential
 * backoff plus jitter. A 4xx that is not a 429 fails immediately - retrying a
 * bad request just burns quota.
 */
public final class RetryingCandleDataClient implements CandleDataClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingCandleDataClient.class);

    private final CandleDataClient delegate;
    private final int maxRetries;
    private final long baseBackoffMillis;
    private final TokenBucketRateLimiter.Sleeper sleeper;

    public RetryingCandleDataClient(CandleDataClient delegate, int maxRetries, long baseBackoffMillis) {
        this(delegate, maxRetries, baseBackoffMillis, millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while backing off", e);
            }
        });
    }

    public RetryingCandleDataClient(CandleDataClient delegate, int maxRetries, long baseBackoffMillis,
                                     TokenBucketRateLimiter.Sleeper sleeper) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.baseBackoffMillis = baseBackoffMillis;
        this.sleeper = sleeper;
    }

    @Override
    public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                           LocalDateTime startTime, LocalDateTime endTime, int intervalMinutes) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return delegate.fetchCandles(symbol, exchange, segment, startTime, endTime, intervalMinutes);
            } catch (GrowwApiException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                last = e;
            } catch (RuntimeException e) {
                last = e;
            }
            if (attempt < maxRetries) {
                sleeper.sleepMillis(backoffMillis(last, attempt));
            }
        }
        throw last;
    }

    /**
     * The server's own {@code Retry-After} beats anything we would invent;
     * otherwise back off exponentially with jitter so several workers that were
     * refused together do not all return at the same instant.
     */
    private long backoffMillis(RuntimeException failure, int attempt) {
        if (failure instanceof GrowwApiException api && api.retryAfterMillis() > 0) {
            log.debug("Honouring the server's Retry-After of {} ms", api.retryAfterMillis());
            return api.retryAfterMillis();
        }
        long backoff = baseBackoffMillis * (1L << attempt);
        return backoff + ThreadLocalRandom.current().nextLong(baseBackoffMillis / 2 + 1);
    }
}
