package com.stockanalyzer.model;

/**
 * One window during a session in which a gain was available: buy at
 * {@code entryTs} for {@code entryPrice}, sell at {@code exitTs} for
 * {@code exitPrice}. {@code rank} 1 is the largest gain of the session.
 */
public record GainOpportunity(int rank,
                              long entryTs,
                              long exitTs,
                              double entryPrice,
                              double exitPrice,
                              double gainPct,
                              int durationMinutes) {
}
