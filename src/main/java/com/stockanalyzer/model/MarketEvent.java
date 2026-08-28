package com.stockanalyzer.model;

import java.time.LocalDate;

/**
 * A named, human-legible thing that happened at a point in a session
 * (an opening-range breakout, a volume spike). Events are both model features
 * and the reason text an alert can carry.
 */
public record MarketEvent(String symbol,
                          LocalDate sessionDate,
                          long tsEpoch,
                          EventType type,
                          double strength) {
}
