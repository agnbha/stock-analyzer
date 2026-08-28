package com.stockanalyzer.model;

import java.time.LocalDate;

/**
 * One notification. {@code idempotencyKey} is what stops a daemon restart from
 * re-firing everything already sent today.
 */
public record Alert(LocalDate sessionDate,
                    long firedAtEpoch,
                    String symbol,
                    String rule,
                    AlertSeverity severity,
                    String title,
                    String message,
                    String idempotencyKey) {
}
