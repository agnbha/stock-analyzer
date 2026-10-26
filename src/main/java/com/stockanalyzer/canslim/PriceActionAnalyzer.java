package com.stockanalyzer.canslim;

import java.util.List;

/**
 * Turns a stock's daily bars into the readings the CAN SLIM rules need: where
 * it is against its averages and its high, whether it is building a base, and
 * whether it has broken out of one.
 *
 * <p><b>Bases.</b> A base is found the way a chart reader finds one: the highest
 * high that nothing since has exceeded, at least {@code minBaseSessions} old,
 * is the pivot; the deepest low since it is the base's depth. A base deeper than
 * {@code maxBaseDepthPct}, or one that did not follow a real advance, is not a
 * base. This recognises flat bases, cups and saucers by their common shape - a
 * consolidation under a resistance level after a run-up - without trying to
 * classify the pattern, which is where chart-pattern code usually goes wrong.
 *
 * <p><b>Breakouts</b> are a close through a base's pivot, from at or below it,
 * on volume at least {@code breakoutVolumeRatio} times the 50-day average.
 * The base is recomputed as of each session in the look-back, so a breakout is
 * judged against the pivot that stood on the day it happened.
 */
public final class PriceActionAnalyzer {

    private static final int RS_QUARTER = 63;
    private static final int RALLY_WINDOW = 126;
    private static final int FIFTY_DAY_BREAK_WINDOW = 5;
    private static final int EXHAUSTION_WINDOW = 3;
    private static final double MAX_UP_DOWN_RATIO = 99.0;

    private final CanSlimCriteria criteria;

    public PriceActionAnalyzer(CanSlimCriteria criteria) {
        this.criteria = criteria;
    }

    /** Null with fewer than two bars: there is nothing to compare. */
    public PriceAction analyze(List<DailyBar> bars) {
        if (bars.size() < 2) {
            return null;
        }
        int t = bars.size() - 1;
        DailyBar latest = bars.get(t);
        double close = latest.close();

        Double averageVolume50 = Bars.averageVolumeBefore(bars, t, 50);
        Double volumeRatio = averageVolume50 == null || averageVolume50 == 0
                ? null : latest.volume() / averageVolume50;
        double high52w = Bars.maxHigh(bars, t - 251, t);

        PriceAction.Breakout breakout = latestBreakout(bars);
        return new PriceAction(
                latest.date(),
                bars.size(),
                close,
                bars.get(t - 1).close(),
                Bars.sma(bars, t, 50),
                Bars.sma(bars, t, 200),
                averageVolume50,
                volumeRatio,
                dryUpRatio(bars),
                high52w,
                (high52w - close) / high52w * 100.0,
                upDownVolumeRatio(bars),
                weightedReturn(bars),
                advanceFromLow(bars),
                t >= CanSlimCriteria.CLIMAX_WINDOW
                        ? Bars.pctChange(bars.get(t - CanSlimCriteria.CLIMAX_WINDOW).close(), close) : null,
                baseAsOf(bars, t),
                breakout,
                fiftyDayBreak(bars),
                breakout != null && isExhaustionDay(bars, t - breakout.sessionsAgo()));
    }

    /**
     * The base standing on session {@code index}, judged from the highs before
     * it; null when the stock is not in a valid one.
     */
    PriceAction.Base baseAsOf(List<DailyBar> bars, int index) {
        if (index < criteria.minBaseSessions()) {
            return null;
        }
        // Walking left, each high that nothing since has exceeded is higher than
        // the last one found; the base's pivot is the highest of them whose
        // correction still fits the depth limit. Stopping at the first would
        // take a high from inside a flat base for its pivot.
        double laterHigh = Double.NEGATIVE_INFINITY;
        double lowSince = Double.POSITIVE_INFINITY;
        int pivotIndex = -1;
        double depth = 0;
        for (int i = index - 1; i >= Math.max(0, index - criteria.maxBaseSessions()); i--) {
            DailyBar bar = bars.get(i);
            lowSince = Math.min(lowSince, bar.low());
            if (bar.high() >= laterHigh && index - i >= criteria.minBaseSessions()) {
                double candidateDepth = (bar.high() - lowSince) / bar.high() * 100.0;
                if (candidateDepth > criteria.maxBaseDepthPct()) {
                    break;
                }
                pivotIndex = i;
                depth = candidateDepth;
            }
            laterHigh = Math.max(laterHigh, bar.high());
        }
        if (pivotIndex < 0) {
            return null;
        }
        double pivot = bars.get(pivotIndex).high();
        Double priorAdvance = null;
        int runUpStart = Math.max(0, pivotIndex - RS_QUARTER);
        if (pivotIndex - runUpStart >= 20) {
            priorAdvance = Bars.pctChange(Bars.minLow(bars, runUpStart, pivotIndex - 1), pivot);
            if (priorAdvance < criteria.minPriorAdvancePct()) {
                return null;
            }
        }
        return new PriceAction.Base(bars.get(pivotIndex).date(), pivot, index - pivotIndex, depth,
                priorAdvance, Bars.pctChange(pivot, bars.get(index).close()));
    }

    private PriceAction.Breakout latestBreakout(List<DailyBar> bars) {
        int t = bars.size() - 1;
        for (int b = t; b >= Math.max(1, t - criteria.breakoutLookbackSessions()); b--) {
            PriceAction.Base base = baseAsOf(bars, b);
            if (base == null) {
                continue;
            }
            double pivot = base.pivot();
            boolean crossed = bars.get(b).close() > pivot && bars.get(b - 1).close() <= pivot;
            Double average = Bars.averageVolumeBefore(bars, b, 50);
            if (!crossed || average == null || average == 0) {
                continue;
            }
            double ratio = bars.get(b).volume() / average;
            if (ratio >= criteria.breakoutVolumeRatio()) {
                return new PriceAction.Breakout(bars.get(b).date(), pivot, ratio, t - b,
                        Bars.pctChange(pivot, bars.get(t).close()),
                        Bars.pctChange(pivot, Bars.maxHigh(bars, b, t)));
            }
        }
        return null;
    }

    /** A close below the 50-day average within the last few sessions, while still below it. */
    private static PriceAction.FiftyDayBreak fiftyDayBreak(List<DailyBar> bars) {
        int t = bars.size() - 1;
        Double sma = Bars.sma(bars, t, 50);
        if (sma == null || bars.get(t).close() >= sma) {
            return null;
        }
        for (int d = t; d >= Math.max(1, t - FIFTY_DAY_BREAK_WINDOW + 1); d--) {
            Double today = Bars.sma(bars, d, 50);
            Double yesterday = Bars.sma(bars, d - 1, 50);
            if (today == null || yesterday == null) {
                return null;
            }
            if (bars.get(d).close() < today && bars.get(d - 1).close() >= yesterday) {
                Double average = Bars.averageVolumeBefore(bars, d, 50);
                return new PriceAction.FiftyDayBreak(bars.get(d).date(),
                        average == null || average == 0 ? null : bars.get(d).volume() / average);
            }
        }
        return null;
    }

    /**
     * O'Neil's exhaustion signal: the largest one-day gain since the breakout,
     * on the heaviest volume since it, in the last few sessions.
     */
    private static boolean isExhaustionDay(List<DailyBar> bars, int breakoutIndex) {
        int t = bars.size() - 1;
        int biggestGain = -1;
        int heaviest = -1;
        for (int i = breakoutIndex + 1; i <= t; i++) {
            double gain = Bars.pctChange(bars.get(i - 1).close(), bars.get(i).close());
            if (biggestGain < 0 || gain > Bars.pctChange(bars.get(biggestGain - 1).close(),
                    bars.get(biggestGain).close())) {
                biggestGain = i;
            }
            if (heaviest < 0 || bars.get(i).volume() > bars.get(heaviest).volume()) {
                heaviest = i;
            }
        }
        return biggestGain > 0 && biggestGain == heaviest && biggestGain > t - EXHAUSTION_WINDOW
                && bars.get(biggestGain).close() > bars.get(biggestGain - 1).close();
    }

    private static Double dryUpRatio(List<DailyBar> bars) {
        int t = bars.size() - 1;
        if (bars.size() < 50) {
            return null;
        }
        double recent = 0;
        for (int i = t - 9; i <= t; i++) {
            recent += bars.get(i).volume();
        }
        double longer = 0;
        for (int i = t - 49; i <= t; i++) {
            longer += bars.get(i).volume();
        }
        return longer == 0 ? null : (recent / 10) / (longer / 50);
    }

    private static Double upDownVolumeRatio(List<DailyBar> bars) {
        int t = bars.size() - 1;
        double up = 0;
        double down = 0;
        for (int i = Math.max(1, t - 49); i <= t; i++) {
            double change = bars.get(i).close() - bars.get(i - 1).close();
            if (change > 0) {
                up += bars.get(i).volume();
            } else if (change < 0) {
                down += bars.get(i).volume();
            }
        }
        if (down == 0) {
            return up == 0 ? null : MAX_UP_DOWN_RATIO;
        }
        return Math.min(up / down, MAX_UP_DOWN_RATIO);
    }

    /**
     * IBD's relative-strength input: the last quarter's return counts double.
     * A younger stock (a recent listing, often exactly the kind CAN SLIM looks
     * for) is weighted over the quarters it has; less than one quarter is too
     * little to rank.
     */
    static Double weightedReturn(List<DailyBar> bars) {
        int t = bars.size() - 1;
        double close = bars.get(t).close();
        double[] weights = {0.4, 0.2, 0.2, 0.2};
        double weighted = 0;
        double weightUsed = 0;
        for (int q = 0; q < weights.length; q++) {
            int back = RS_QUARTER * (q + 1);
            if (t - back < 0) {
                break;
            }
            weighted += weights[q] * Bars.pctChange(bars.get(t - back).close(), close);
            weightUsed += weights[q];
        }
        return weightUsed == 0 ? null : weighted / weightUsed;
    }

    /** The biggest advance from a low to a later high inside the last six months. */
    private static Double advanceFromLow(List<DailyBar> bars) {
        int t = bars.size() - 1;
        int start = Math.max(0, t - RALLY_WINDOW + 1);
        double lowest = Double.POSITIVE_INFINITY;
        double best = 0;
        for (int i = start; i <= t; i++) {
            lowest = Math.min(lowest, bars.get(i).low());
            best = Math.max(best, Bars.pctChange(lowest, bars.get(i).high()));
        }
        return best;
    }
}
