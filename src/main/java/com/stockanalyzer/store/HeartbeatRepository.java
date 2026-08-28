package com.stockanalyzer.store;

import java.time.LocalDate;
import java.util.Optional;

/** Liveness for the market-hours daemon, so a silently dead one is detectable. */
public interface HeartbeatRepository {

    void beat(LocalDate sessionDate, int symbolsTracked, boolean degraded, String note);

    Optional<Heartbeat> latest();

    record Heartbeat(LocalDate sessionDate, long lastTickEpoch, int symbolsTracked,
                     boolean degraded, String note) {
    }
}
