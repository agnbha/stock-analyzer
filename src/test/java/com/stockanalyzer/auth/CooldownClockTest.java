package com.stockanalyzer.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a cooldown names the time it ends, in the log and in the error a caller sees. Each case is a local time built
 * from its parts, so the test does not depend on the zone it runs in or on when it runs.
 */
class CooldownClockTest {

    private static String shown(LocalDateTime local) {
        Instant until = local.atZone(ZoneId.systemDefault()).toInstant();
        return new GrowwAuthenticator.Cooldown(until, "a reason", null).untilLocalTime();
    }

    @Test
    @DisplayName("a time that falls on a whole minute still shows its seconds, as 11:36:00 and not 11:36")
    void aWholeMinuteKeepsItsSeconds() {
        assertEquals("11:36:00", shown(LocalDateTime.of(2026, 11, 26, 11, 36, 0)));
    }

    @Test
    @DisplayName("a time with seconds shows them, and a fraction of a second is dropped, not rounded")
    void secondsAreWhole() {
        assertEquals("11:36:07", shown(LocalDateTime.of(2026, 11, 26, 11, 36, 7, 999_000_000)));
    }

    @Test
    @DisplayName("midnight, and the minute before it, are written the same way")
    void theEdgesOfTheDay() {
        assertEquals("00:00:00", shown(LocalDateTime.of(2026, 11, 26, 0, 0, 0)));
        assertEquals("23:59:59", shown(LocalDateTime.of(2026, 11, 26, 23, 59, 59)));
    }
}
