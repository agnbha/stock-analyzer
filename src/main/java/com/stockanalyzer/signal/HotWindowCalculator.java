package com.stockanalyzer.signal;

import com.stockanalyzer.model.HotWindow;
import com.stockanalyzer.store.OpportunityRow;
import com.stockanalyzer.util.MarketClock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns months of stored opportunities into the time-of-day prior: how often
 * each bucket of the session has contained a top-N entry, and what gain
 * followed.
 *
 * <p>This is the baseline any model has to beat, and it is what the
 * recurring-timestamp alerts fire from. Windows are ranked by the Wilson lower
 * confidence bound rather than the raw hit rate, which discounts every estimate
 * by how little evidence stands behind it: 2 hits out of 2 falls from 1.00 to
 * about 0.34, while 40 out of 100 falls only from 0.40 to about 0.31.
 *
 * <p>Note that the bound alone does not reorder those two - 0.34 still beats
 * 0.31. What actually keeps thin buckets out of the alerts is the minimum
 * session count ({@code alerts.hotwindow.min-sessions}); the bound is what
 * orders the buckets that clear it.
 */
public final class HotWindowCalculator {

    private static final double Z = 1.96;

    private final MarketClock clock;
    private final int bucketMinutes;

    public HotWindowCalculator(MarketClock clock, int bucketMinutes) {
        if (bucketMinutes < 1) {
            throw new IllegalArgumentException("bucketMinutes must be at least 1");
        }
        this.clock = clock;
        this.bucketMinutes = bucketMinutes;
    }

    /** Per-symbol windows plus market-wide ones (symbol {@code null}). */
    public List<HotWindow> compute(List<OpportunityRow> opportunities, int lookbackDays) {
        Map<String, Map<Integer, List<Double>>> gainsBySymbolBucket = new LinkedHashMap<>();
        Map<String, Set<LocalDate>> sessionsBySymbol = new HashMap<>();
        Map<Integer, List<Double>> marketBuckets = new LinkedHashMap<>();
        Set<LocalDate> allSessions = new TreeSet<>();

        for (OpportunityRow row : opportunities) {
            int bucket = bucketOf(row.opportunity().entryTs());
            gainsBySymbolBucket
                    .computeIfAbsent(row.symbol(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(bucket, key -> new ArrayList<>())
                    .add(row.opportunity().gainPct());
            sessionsBySymbol.computeIfAbsent(row.symbol(), key -> new TreeSet<>()).add(row.sessionDate());
            marketBuckets.computeIfAbsent(bucket, key -> new ArrayList<>()).add(row.opportunity().gainPct());
            allSessions.add(row.sessionDate());
        }

        List<HotWindow> windows = new ArrayList<>();
        gainsBySymbolBucket.forEach((symbol, buckets) -> {
            int sessions = sessionsBySymbol.getOrDefault(symbol, Set.of()).size();
            buckets.forEach((bucket, gains) -> windows.add(window(symbol, bucket, gains, sessions, lookbackDays)));
        });
        marketBuckets.forEach((bucket, gains) ->
                windows.add(window(null, bucket, gains, allSessions.size(), lookbackDays)));

        windows.sort(Comparator.comparingDouble(HotWindow::hitRateLcb).reversed());
        return windows;
    }

    private HotWindow window(String symbol, int bucketStartMinute, List<Double> gains, int sessions,
                             int lookbackDays) {
        int hits = gains.size();
        int denominator = Math.max(sessions, hits);
        double hitRate = denominator == 0 ? 0 : hits / (double) denominator;
        List<Double> sorted = gains.stream().sorted().toList();
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        return new HotWindow(symbol, bucketStartMinute, bucketMinutes, lookbackDays, hits, denominator,
                hitRate, wilsonLowerBound(hits, denominator), mean, median);
    }

    private int bucketOf(long entryTs) {
        int minute = Math.max(clock.minutesSinceOpen(entryTs), 0);
        return minute - (minute % bucketMinutes);
    }

    /**
     * Wilson score lower bound - the "how sure are we" half of a hit rate. It
     * shrinks an estimate toward zero in proportion to how little evidence
     * supports it, heavily for a handful of sessions and barely at all for a
     * hundred.
     */
    public static double wilsonLowerBound(int hits, int trials) {
        if (trials <= 0) {
            return 0.0;
        }
        double p = hits / (double) trials;
        double denominator = 1 + Z * Z / trials;
        double centre = p + Z * Z / (2.0 * trials);
        double margin = Z * Math.sqrt((p * (1 - p) + Z * Z / (4.0 * trials)) / trials);
        return Math.max((centre - margin) / denominator, 0.0);
    }
}
