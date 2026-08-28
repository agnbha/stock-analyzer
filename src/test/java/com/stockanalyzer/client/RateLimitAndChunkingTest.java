package com.stockanalyzer.client;

import com.stockanalyzer.model.StockCandleSeries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitAndChunkingTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 9, 15);

    @Test
    @DisplayName("the token bucket spaces requests once the per-second ceiling is reached")
    void spacesRequestsOverASecond() {
        AtomicLong nanos = new AtomicLong();
        List<Long> sleeps = new ArrayList<>();
        // A fake clock that only moves when the limiter sleeps, so the test never really waits.
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 100, nanos::get, millis -> {
            sleeps.add(millis);
            nanos.addAndGet(millis * 1_000_000L);
        });

        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }

        // Two per second means two go straight through, then a wait, then two
        // more, then a wait for the fifth.
        assertEquals(2, sleeps.size(), "two waits for five requests at two per second");
        assertTrue(sleeps.stream().allMatch(millis -> millis > 0 && millis <= 1000), "waits were " + sleeps);
    }

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
        private int calls;

        private FailingClient(int status) {
            this.status = status;
        }

        @Override
        public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                               LocalDateTime start, LocalDateTime end, int intervalMinutes) {
            calls++;
            throw new GrowwApiException("failing with " + status, status);
        }
    }
}
