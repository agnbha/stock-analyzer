package com.stockanalyzer.signal;

import com.stockanalyzer.model.HotWindow;
import com.stockanalyzer.store.OpportunityRow;
import com.stockanalyzer.util.MarketClock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

    /**
     * Per-symbol windows plus market-wide ones (symbol {@code null}).
     *
     * <p>A hit is a <em>session</em> that had an entry in the bucket, and the
     * hit rate is hits over sessions, so the two are counted in the same unit.
     * For a symbol that is its sessions; for the market-wide window it is
     * symbol-sessions, since each symbol's session is a separate chance for the
     * bucket to contain an entry. Counting opportunities instead - three
     * windows can open in one bucket, and fifty symbols open in the same
     * minute - puts more hits than sessions in the numerator, which the
     * denominator then stretches to match: a rate of exactly 1.0 that says
     * nothing, and a lower bound of 0.99 on a coin flip.
     *
     * <p>Sessions here are those in which at least one window was found, so
     * quiet days with none are not in the denominator; the rates run slightly
     * high for that reason.
     */
    public List<HotWindow> compute(List<OpportunityRow> opportunities, int lookbackDays) {
        Map<String, Map<Integer, BucketEvidence>> bySymbolBucket = new LinkedHashMap<>();
        Map<String, Set<LocalDate>> sessionsBySymbol = new HashMap<>();
        Map<Integer, BucketEvidence> marketBuckets = new LinkedHashMap<>();
        Set<String> allSymbolSessions = new TreeSet<>();

        for (OpportunityRow row : opportunities) {
            int bucket = bucketOf(row.opportunity().entryTs());
            double gain = row.opportunity().gainPct();
            String symbolSession = row.symbol() + "|" + row.sessionDate();

            bySymbolBucket
                    .computeIfAbsent(row.symbol(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(bucket, key -> new BucketEvidence())
                    .add(row.sessionDate().toString(), gain);
            sessionsBySymbol.computeIfAbsent(row.symbol(), key -> new TreeSet<>()).add(row.sessionDate());
            marketBuckets.computeIfAbsent(bucket, key -> new BucketEvidence()).add(symbolSession, gain);
            allSymbolSessions.add(symbolSession);
        }

        List<HotWindow> windows = new ArrayList<>();
        bySymbolBucket.forEach((symbol, buckets) -> {
            int sessions = sessionsBySymbol.getOrDefault(symbol, Set.of()).size();
            buckets.forEach((bucket, evidence) ->
                    windows.add(window(symbol, bucket, evidence, sessions, lookbackDays)));
        });
        marketBuckets.forEach((bucket, evidence) ->
                windows.add(window(null, bucket, evidence, allSymbolSessions.size(), lookbackDays)));

        windows.sort(Comparator.comparingDouble(HotWindow::hitRateLcb).reversed());
        return windows;
    }

    /** The sessions that had an entry in one bucket, and every gain that followed one. */
    private static final class BucketEvidence {
        private final Set<String> sessions = new HashSet<>();
        private final List<Double> gains = new ArrayList<>();

        void add(String session, double gain) {
            sessions.add(session);
            gains.add(gain);
        }
    }

    private HotWindow window(String symbol, int bucketStartMinute, BucketEvidence evidence, int sessions,
                             int lookbackDays) {
        int hits = evidence.sessions.size();
        double hitRate = sessions == 0 ? 0 : hits / (double) sessions;
        List<Double> sorted = evidence.gains.stream().sorted().toList();
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        return new HotWindow(symbol, bucketStartMinute, bucketMinutes, lookbackDays, hits, sessions,
                hitRate, wilsonLowerBound(hits, sessions), mean, median);
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
