package com.stockanalyzer.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * All conversions between the epoch seconds the API returns and the local
 * session date humans and the backfill planner use. Every timestamp in the
 * database is UTC epoch seconds; every {@code session_date} is derived here.
 */
public final class MarketClock {

    private final ZoneId zone;
    private final LocalTime sessionOpen;
    private final LocalTime sessionClose;

    public MarketClock(ZoneId zone, LocalTime sessionOpen, LocalTime sessionClose) {
        this.zone = zone;
        this.sessionOpen = sessionOpen;
        this.sessionClose = sessionClose;
    }

    public static MarketClock nse() {
        return new MarketClock(ZoneId.of("Asia/Kolkata"), LocalTime.of(9, 15), LocalTime.of(15, 30));
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalTime sessionOpen() {
        return sessionOpen;
    }

    public LocalTime sessionClose() {
        return sessionClose;
    }

    public LocalDate sessionDateOf(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate();
    }

    public LocalTime timeOf(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalTime();
    }

    public long epochOf(LocalDate date, LocalTime time) {
        return ZonedDateTime.of(date, time, zone).toEpochSecond();
    }

    public long sessionOpenEpoch(LocalDate date) {
        return epochOf(date, sessionOpen);
    }

    public long sessionCloseEpoch(LocalDate date) {
        return epochOf(date, sessionClose);
    }

    /** Minutes since the open; negative before it. Used for time-of-day bucketing. */
    public int minutesSinceOpen(long epochSeconds) {
        LocalDate date = sessionDateOf(epochSeconds);
        return (int) Duration.ofSeconds(epochSeconds - sessionOpenEpoch(date)).toMinutes();
    }

    public int sessionLengthMinutes() {
        return (int) Duration.between(sessionOpen, sessionClose).toMinutes();
    }

    public boolean isWithinSession(long epochSeconds) {
        LocalTime t = timeOf(epochSeconds);
        return !t.isBefore(sessionOpen) && !t.isAfter(sessionClose);
    }
}
