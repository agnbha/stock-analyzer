package com.stockanalyzer.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final List<Long> sleeps = new ArrayList<>();

    /** A clock that only moves when the limiter sleeps, so tests never really wait. */
    private TokenBucketRateLimiter limiter(int perSecond, int perMinute, int perDay) {
        return new TokenBucketRateLimiter(perSecond, perMinute, perDay, nanos::get, millis -> {
            sleeps.add(millis);
            nanos.addAndGet(millis * 1_000_000L);
        });
    }

    @Test
    @DisplayName("spaces requests once the per-second ceiling is reached")
    void spacesRequestsOverASecond() {
        TokenBucketRateLimiter limiter = limiter(2, 100, 0);

        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }

        assertEquals(2, sleeps.size(), "two waits for five requests at two per second");
        assertTrue(sleeps.stream().allMatch(millis -> millis > 0 && millis <= 1000), "waits were " + sleeps);
    }

    @Test
    @DisplayName("the per-minute ceiling holds even when the per-second one has room")
    void enforcesThePerMinuteCeiling() {
        TokenBucketRateLimiter limiter = limiter(100, 3, 0);

        for (int i = 0; i < 4; i++) {
            limiter.acquire();
        }

        assertEquals(1, sleeps.size(), "the fourth request waited for the minute window");
        assertTrue(sleeps.getFirst() > 1_000, "it waited most of a minute, not a second: " + sleeps);
    }

    @Test
    @DisplayName("the daily ceiling is enforced too")
    void enforcesTheDailyCeiling() {
        TokenBucketRateLimiter limiter = limiter(1000, 1000, 3);

        for (int i = 0; i < 4; i++) {
            limiter.acquire();
        }

        assertEquals(1, sleeps.size());
        assertTrue(sleeps.getFirst() > 60_000, "the wait spans into the day window: " + sleeps);
    }

    @Test
    @DisplayName("a 429 holds back every caller, not just the one that was refused")
    void penaltyAppliesToEveryCaller() {
        TokenBucketRateLimiter limiter = limiter(1000, 1000, 0);

        limiter.acquire();
        limiter.penalise(5_000);
        limiter.acquire();

        assertEquals(1, sleeps.size(), "the next caller waited even though it had tokens to spare");
        assertEquals(5_000, sleeps.getFirst());
    }

    @Test
    @DisplayName("a longer penalty replaces a shorter one, never the other way round")
    void penaltiesTakeTheLongestWait() {
        TokenBucketRateLimiter limiter = limiter(1000, 1000, 0);

        limiter.penalise(10_000);
        limiter.penalise(1_000);
        limiter.acquire();

        assertEquals(10_000, sleeps.getFirst(), "the shorter penalty must not shorten the hold");
    }

    @Test
    @DisplayName("an ignored penalty is a no-op")
    void nonPositivePenaltiesDoNothing() {
        TokenBucketRateLimiter limiter = limiter(1000, 1000, 0);

        limiter.penalise(0);
        limiter.penalise(-5);
        limiter.acquire();

        assertTrue(sleeps.isEmpty());
    }

    @Test
    @DisplayName("a limiter with no ceilings at all is a configuration error, not a free pass")
    void refusesToBeUnlimited() {
        assertThrows(IllegalArgumentException.class, () -> limiter(0, 0, 0));
    }
}
