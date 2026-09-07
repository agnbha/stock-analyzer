package com.stockanalyzer.intraday;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.GainOpportunity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The default detector: the top N non-overlapping gain windows of a session.
 *
 * <p>The best single window inside a candle range is one left-to-right pass,
 * tracking the cheapest entry seen so far and evaluating each candle as an exit
 * (Kadane-style, O(n)). Top-N is that pass applied repeatedly: take the best
 * window, then recurse into the candles strictly before its entry and strictly
 * after its exit, always expanding the most promising remaining segment first.
 * The windows therefore cannot overlap, so the answer is N genuinely distinct
 * opportunities rather than N shifted views of one move.
 *
 * <p>{@code minGainPct} is a floor on what counts as an opportunity at all,
 * not a tie-breaker. A session whose best window never clears it yields fewer
 * than N windows, and a session that never clears it at all yields none -
 * which is the honest answer for a day that offered no tradable move. Set it
 * above the round-trip cost of a trade and a recorded window always describes
 * something worth acting on.
 */
public final class TopKNonOverlappingDetector implements GainOpportunityDetector {

    private final int topN;
    private final PriceBasis priceBasis;
    private final int minHoldCandles;
    private final double minGainPct;

    public TopKNonOverlappingDetector(int topN, PriceBasis priceBasis, int minHoldCandles, double minGainPct) {
        if (topN < 1) {
            throw new IllegalArgumentException("topN must be at least 1");
        }
        if (minHoldCandles < 1) {
            throw new IllegalArgumentException("minHoldCandles must be at least 1: the exit must be a later candle");
        }
        this.topN = topN;
        this.priceBasis = priceBasis;
        this.minHoldCandles = minHoldCandles;
        this.minGainPct = minGainPct;
    }

    /**
     * Identifies the rules that produced a result, so a changed detector writes
     * a parallel series rather than quietly restating the old one under its
     * name. The gain floor is part of those rules and so appears here - but
     * only when it is set, which keeps the tag stable for the original
     * floor-less runs already in the database.
     */
    @Override
    public String version() {
        return "topk-nonoverlap/" + priceBasis.tag() + minGainTag() + "/v1";
    }

    private String minGainTag() {
        if (minGainPct <= 0) {
            return "";
        }
        return "/min" + BigDecimal.valueOf(minGainPct).stripTrailingZeros().toPlainString();
    }

    @Override
    public List<GainOpportunity> detect(List<Candle> candles) {
        if (candles == null || candles.size() <= minHoldCandles) {
            return List.of();
        }

        PriorityQueue<Window> queue = new PriorityQueue<>(Comparator.comparingDouble(Window::gainPct).reversed());
        addBest(queue, candles, 0, candles.size() - 1);

        List<GainOpportunity> found = new ArrayList<>(topN);
        while (found.size() < topN && !queue.isEmpty()) {
            Window best = queue.poll();
            // The queue is ordered by gain, so once the head is below the floor nothing better remains.
            if (best.gainPct() < minGainPct) {
                break;
            }
            Candle entry = candles.get(best.entryIndex());
            Candle exit = candles.get(best.exitIndex());
            found.add(new GainOpportunity(
                    found.size() + 1,
                    entry.epochSeconds(),
                    exit.epochSeconds(),
                    priceBasis.entryPrice(entry),
                    priceBasis.exitPrice(exit),
                    best.gainPct(),
                    (int) ((exit.epochSeconds() - entry.epochSeconds()) / 60)));

            addBest(queue, candles, best.segmentLo(), best.entryIndex() - 1);
            addBest(queue, candles, best.exitIndex() + 1, best.segmentHi());
        }
        return List.copyOf(found);
    }

    private void addBest(PriorityQueue<Window> queue, List<Candle> candles, int lo, int hi) {
        Window window = bestWindow(candles, lo, hi);
        if (window != null) {
            queue.add(window);
        }
    }

    /**
     * Best entry/exit pair within {@code [lo, hi]}, honouring the minimum hold.
     * Returns null when the segment is too short to hold one.
     */
    private Window bestWindow(List<Candle> candles, int lo, int hi) {
        if (lo < 0 || hi >= candles.size() || hi - lo < minHoldCandles) {
            return null;
        }

        int cheapestIndex = -1;
        double cheapestPrice = Double.MAX_VALUE;
        Window best = null;

        for (int exitIndex = lo + minHoldCandles; exitIndex <= hi; exitIndex++) {
            int newlyEligibleEntry = exitIndex - minHoldCandles;
            double entryPrice = priceBasis.entryPrice(candles.get(newlyEligibleEntry));
            if (entryPrice > 0 && entryPrice < cheapestPrice) {
                cheapestPrice = entryPrice;
                cheapestIndex = newlyEligibleEntry;
            }
            if (cheapestIndex < 0) {
                continue;
            }
            double exitPrice = priceBasis.exitPrice(candles.get(exitIndex));
            double gainPct = (exitPrice - cheapestPrice) / cheapestPrice * 100.0;
            if (best == null || gainPct > best.gainPct()) {
                best = new Window(lo, hi, cheapestIndex, exitIndex, gainPct);
            }
        }
        return best;
    }

    /** One candidate window plus the segment it was found in, so the split points are known. */
    private record Window(int segmentLo, int segmentHi, int entryIndex, int exitIndex, double gainPct) {
    }
}
