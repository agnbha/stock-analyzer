package com.stockanalyzer.model;

/**
 * How often a bucket of the trading day has historically contained a top-N gain
 * entry. Rank by {@link #hitRateLcb()} (Wilson lower bound) rather than the raw
 * hit rate, so a 2-of-2 bucket cannot outrank a 40-of-100 bucket.
 *
 * <p>{@code symbol} is null for a market-wide window.
 */
public record HotWindow(String symbol,
                        int bucketStartMinute,
                        int bucketMinutes,
                        int lookbackDays,
                        int hits,
                        int sessions,
                        double hitRate,
                        double hitRateLcb,
                        double meanGainPct,
                        double medianGainPct) {
}
