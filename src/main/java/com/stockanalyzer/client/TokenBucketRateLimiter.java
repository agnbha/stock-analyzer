package com.stockanalyzer.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Enforces both a per-second and a per-minute request ceiling using sliding
 * windows of recent request times.
 *
 * <p>One instance is shared by every caller - the nightly batch and the live
 * monitor included - so the process as a whole cannot exceed the provider's
 * limits no matter how many threads are fetching.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final int perSecond;
    private final int perMinute;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final Deque<Long> recent = new ArrayDeque<>();

    public TokenBucketRateLimiter(int perSecond, int perMinute) {
        this(perSecond, perMinute, System::nanoTime, millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while rate limiting", e);
            }
        });
    }

    public TokenBucketRateLimiter(int perSecond, int perMinute, LongSupplier nanoTime, Sleeper sleeper) {
        this.perSecond = perSecond;
        this.perMinute = perMinute;
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
                long inLastSecond = countSince(now - 1_000_000_000L);
                long inLastMinute = recent.size();
                if (inLastSecond < perSecond && inLastMinute < perMinute) {
                    recent.addLast(now);
                    return;
                }
                waitMillis = millisUntilSlotFrees(now, inLastSecond >= perSecond);
            }
            sleeper.sleepMillis(Math.max(waitMillis, 1));
        }
    }

    private void prune(long now) {
        long minuteAgo = now - 60_000_000_000L;
        while (!recent.isEmpty() && recent.peekFirst() <= minuteAgo) {
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

    private long millisUntilSlotFrees(long now, boolean secondLimitHit) {
        if (secondLimitHit) {
            long oldestInSecond = now;
            for (Long timestamp : recent) {
                if (timestamp > now - 1_000_000_000L) {
                    oldestInSecond = timestamp;
                    break;
                }
            }
            return (oldestInSecond + 1_000_000_000L - now) / 1_000_000L;
        }
        Long oldest = recent.peekFirst();
        return oldest == null ? 1 : (oldest + 60_000_000_000L - now) / 1_000_000L;
    }

    /** Separated so tests can drive a fake clock instead of really sleeping. */
    @FunctionalInterface
    public interface Sleeper {
        void sleepMillis(long millis);
    }
}
