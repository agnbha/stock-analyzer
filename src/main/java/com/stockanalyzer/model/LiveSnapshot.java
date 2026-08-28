package com.stockanalyzer.model;

import java.time.LocalDate;
import java.util.List;

/**
 * One frame of the live view. {@code stalenessSeconds} is rendered rather than
 * hidden: a stale number shown as live is worse than no number.
 */
public record LiveSnapshot(LocalDate sessionDate,
                           long asOfEpoch,
                           long nextPollEpoch,
                           long stalenessSeconds,
                           boolean degraded,
                           String degradedReason,
                           List<LiveSymbolState> symbols,
                           List<Alert> recentAlerts) {
}
