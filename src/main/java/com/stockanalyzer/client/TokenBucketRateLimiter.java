package com.stockanalyzer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Enforces several request ceilings at once - per second, per minute and per
 * day - using sliding windows over recent request times.
 *
 * <p>One instance is shared by every caller inside the process, so no number of
 * worker threads can collectively exceed the provider's limits. It cannot see
 * other processes: two JVMs each get their own budget, which is why a backfill
 * should not run while the market-hours daemon is live.
 *
 * <p>The daily window is a guardrail against one runaway process, not an
 * account-wide ledger - it resets when the process restarts.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final long MINUTE_NANOS = 60 * SECOND_NANOS;
    private static final long DAY_NANOS = 24 * 60 * MINUTE_NANOS;

    private final List<Window> windows;
    private final long longestWindowNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final Deque<Long> recent = new ArrayDeque<>();

    private long penaltyUntilNanos = Long.MIN_VALUE;

    public TokenBucketRateLimiter(int perSecond, int perMinute, int perDay) {
        this(perSecond, perMinute, perDay, System::nanoTime, TokenBucketRateLimiter::sleepMillis);
    }

    public TokenBucketRateLimiter(int perSecond, int perMinute, int perDay,
                                  LongSupplier nanoTime, Sleeper sleeper) {
        List<Window> configured = new ArrayList<>(3);
        if (perSecond > 0) {
            configured.add(new Window("per second", perSecond, SECOND_NANOS));
        }
        if (perMinute > 0) {
            configured.add(new Window("per minute", perMinute, MINUTE_NANOS));
        }
        if (perDay > 0) {
            configured.add(new Window("per day", perDay, DAY_NANOS));
        }
        if (configured.isEmpty()) {
            throw new IllegalArgumentException("At least one rate limit must be positive");
        }
        this.windows = List.copyOf(configured);
        this.longestWindowNanos = configured.stream().mapToLong(Window::nanos).max().orElse(MINUTE_NANOS);
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    @Override
    public void acquire() {
        while (true) {
            long waitMillis;
            synchronized (this) {
                long now = nanoTime.getAsLong();
                prune(now);

                if (now < penaltyUntilNanos) {
                    waitMillis = (penaltyUntilNanos - now) / 1_000_000L;
                } else {
                    Window blocking = firstExhaustedWindow(now);
                    if (blocking == null) {
                        recent.addLast(now);
                        return;
                    }
                    waitMillis = millisUntilSlotFrees(now, blocking);
                }
            }
            sleeper.sleepMillis(Math.max(waitMillis, 1));
        }
    }

    @Override
    public synchronized void penalise(long millis) {
        if (millis <= 0) {
            return;
        }
        long until = nanoTime.getAsLong() + millis * 1_000_000L;
        if (until > penaltyUntilNanos) {
            penaltyUntilNanos = until;
            log.warn("Provider asked us to slow down; holding all requests for {} ms", millis);
        }
    }

    private Window firstExhaustedWindow(long now) {
        for (Window window : windows) {
            if (countSince(now - window.nanos()) >= window.limit()) {
                return window;
            }
        }
        return null;
    }

    private void prune(long now) {
        long cutoff = now - longestWindowNanos;
        while (!recent.isEmpty() && recent.peekFirst() <= cutoff) {
            recent.pollFirst();
        }
    }

    /** Strictly newer than the threshold: a request exactly one window old has expired. */
    private long countSince(long threshold) {
        long count = 0;
        for (Long timestamp : recent) {
            if (timestamp > threshold) {
                count++;
            }
        }
        return count;
    }

    /** Wait for the oldest request still inside this window to age out of it. */
    private long millisUntilSlotFrees(long now, Window window) {
        long threshold = now - window.nanos();
        for (Long timestamp : recent) {
            if (timestamp > threshold) {
                return (timestamp + window.nanos() - now) / 1_000_000L;
            }
        }
        return 1;
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rate limiting", e);
        }
    }

    private record Window(String name, int limit, long nanos) {
    }

    /** Separated so tests can drive a fake clock instead of really sleeping. */
    @FunctionalInterface
    public interface Sleeper {
        void sleepMillis(long millis);
    }
}
