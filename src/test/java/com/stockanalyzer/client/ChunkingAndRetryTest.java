package com.stockanalyzer.client;

import com.stockanalyzer.model.StockCandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkingAndRetryTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 9, 15);

    @Test
    @DisplayName("a long range is split into provider-legal chunks that tile it exactly")
    void chunksCoverTheRangeWithoutGaps() {
        CandleRangeChunker chunker = new CandleRangeChunker(5);

        List<CandleRangeChunker.Range> ranges = chunker.chunk(START, START.plusDays(12));

        assertEquals(3, ranges.size());
        assertEquals(START, ranges.getFirst().from());
        assertEquals(START.plusDays(12), ranges.getLast().to());
        for (int i = 1; i < ranges.size(); i++) {
            assertEquals(ranges.get(i - 1).to(), ranges.get(i).from(), "chunks must tile, not overlap or gap");
        }
    }

    @Test
    @DisplayName("a range inside the limit is fetched in one request")
    void shortRangeIsNotChunked() {
        CountingClient counting = new CountingClient();
        ChunkedCandleDataClient client = new ChunkedCandleDataClient(counting, new CandleRangeChunker(5));

        client.fetchCandles("RELIANCE", "NSE", "CASH", START, START.plusDays(2), 1);

        assertEquals(1, counting.calls);
    }

    @Test
    @DisplayName("retries stop at a client error rather than burning quota")
    void doesNotRetryClientErrors() {
        FailingClient failing = new FailingClient(404);
        RetryingCandleDataClient client = new RetryingCandleDataClient(failing, 3, 1, millis -> { });

        assertThrows(GrowwApiException.class,
                () -> client.fetchCandles("X", "NSE", "CASH", START, START.plusDays(1), 1));
        assertEquals(1, failing.calls, "a 404 will never succeed on retry");
    }

    @Test
    @DisplayName("rate limiting and server faults are retried with backoff")
    void retriesRateLimits() {
        FailingClient failing = new FailingClient(429);
        List<Long> backoffs = new ArrayList<>();
        RetryingCandleDataClient client = new RetryingCandleDataClient(failing, 3, 100, backoffs::add);

        assertThrows(GrowwApiException.class,
                () -> client.fetchCandles("X", "NSE", "CASH", START, START.plusDays(1), 1));
        assertEquals(4, failing.calls, "the first attempt plus three retries");
        assertEquals(3, backoffs.size());
        assertTrue(backoffs.get(1) > backoffs.get(0), "backoff grows: " + backoffs);
    }

    @Test
    @DisplayName("the server's Retry-After beats our own backoff")
    void honoursRetryAfter() {
        FailingClient failing = new FailingClient(429, 4_000);
        List<Long> backoffs = new ArrayList<>();
        RetryingCandleDataClient client = new RetryingCandleDataClient(failing, 2, 100, backoffs::add);

        assertThrows(GrowwApiException.class,
                () -> client.fetchCandles("X", "NSE", "CASH", START, START.plusDays(1), 1));

        assertEquals(List.of(4_000L, 4_000L), backoffs, "waited exactly as long as the server asked");
    }

    @Test
    @DisplayName("a 429 holds back every worker, not just the one that was refused")
    void throttlingPenalisesTheSharedLimiter() {
        FailingClient failing = new FailingClient(429, 3_000);
        RecordingRateLimiter limiter = new RecordingRateLimiter();
        RateLimitedCandleDataClient client = new RateLimitedCandleDataClient(failing, limiter);

        assertThrows(GrowwApiException.class,
                () -> client.fetchCandles("X", "NSE", "CASH", START, START.plusDays(1), 1));

        assertEquals(1, limiter.acquired);
        assertEquals(3_000L, limiter.penalty, "the server's wait was applied to the whole limiter");
    }

    @Test
    @DisplayName("a throttle with no Retry-After still pauses everyone briefly")
    void throttlingWithoutAHeaderStillPenalises() {
        RecordingRateLimiter limiter = new RecordingRateLimiter();
        RateLimitedCandleDataClient client = new RateLimitedCandleDataClient(new FailingClient(429, 0), limiter);

        assertThrows(GrowwApiException.class,
                () -> client.fetchCandles("X", "NSE", "CASH", START, START.plusDays(1), 1));

        assertTrue(limiter.penalty > 0, "a 429 always slows the process down");
    }

    @Test
    @DisplayName("an ordinary failure does not penalise the limiter")
    void nonThrottleErrorsDoNotPenalise() {
        RecordingRateLimiter limiter = new RecordingRateLimiter();
        RateLimitedCandleDataClient client = new RateLimitedCandleDataClient(new FailingClient(500, 0), limiter);

        assertThrows(GrowwApiException.class,
                () -> client.fetchCandles("X", "NSE", "CASH", START, START.plusDays(1), 1));

        assertEquals(0, limiter.penalty);
    }

    private static final class RecordingRateLimiter implements RateLimiter {
        private int acquired;
        private long penalty;

        @Override
        public void acquire() {
            acquired++;
        }

        @Override
        public void penalise(long millis) {
            penalty += millis;
        }
    }

    private static final class CountingClient implements CandleDataClient {
        private int calls;

        @Override
        public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                               LocalDateTime start, LocalDateTime end, int intervalMinutes) {
            calls++;
            return new StockCandleSeries(symbol, exchange, segment, List.of());
        }
    }

    private static final class FailingClient implements CandleDataClient {
        private final int status;
        private final long retryAfterMillis;
        private int calls;

        private FailingClient(int status) {
            this(status, 0);
        }

        private FailingClient(int status, long retryAfterMillis) {
            this.status = status;
            this.retryAfterMillis = retryAfterMillis;
        }

        @Override
        public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                               LocalDateTime start, LocalDateTime end, int intervalMinutes) {
            calls++;
            throw new GrowwApiException("failing with " + status, status, retryAfterMillis);
        }
    }
}
