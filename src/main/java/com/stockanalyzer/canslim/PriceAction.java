package com.stockanalyzer.canslim;

import java.time.LocalDate;

/**
 * Everything the price-based rules read about one stock, as of its latest bar.
 * Fields are null where there is not enough history to compute them.
 *
 * @param volumeRatio        latest volume over the 50-day average before it
 * @param dryUpRatio         10-day average volume over the 50-day: below 1 means volume is drying up in the base
 * @param pctFromHigh        how far the close sits below the 52-week (or available) high, as a positive %
 * @param upDownVolumeRatio  volume on up days over volume on down days, last 50 sessions
 * @param weightedReturn     the RS input: 40% on the last quarter, 20% on each of the three before
 * @param advanceFromLowPct  the largest advance off a low in the last six months - did it have a rally to end
 * @param gainOverClimaxWindowPct the gain over the last {@link CanSlimCriteria#CLIMAX_WINDOW} sessions
 * @param base               the consolidation the stock is in now, if it is in one
 * @param breakout           the latest breakout from a base, if one happened recently
 * @param fiftyDayBreak      a recent close below the 50-day average, if the stock is still below it
 * @param exhaustionDay      the biggest one-day gain since the breakout came in the last few sessions on its heaviest volume
 */
public record PriceAction(
        LocalDate asOf,
        int sessions,
        double close,
        double previousClose,
        Double sma50,
        Double sma200,
        Double averageVolume50,
        Double volumeRatio,
        Double dryUpRatio,
        Double high52w,
        Double pctFromHigh,
        Double upDownVolumeRatio,
        Double weightedReturn,
        Double advanceFromLowPct,
        Double gainOverClimaxWindowPct,
        Base base,
        Breakout breakout,
        FiftyDayBreak fiftyDayBreak,
        boolean exhaustionDay) {

    /**
     * A consolidation under a pivot.
     *
     * @param pivot         the base's left-side high: the price a breakout has to clear
     * @param sessions      how long the stock has stayed under the pivot
     * @param depthPct      the deepest correction from the pivot inside the base
     * @param priorAdvancePct the run-up into the pivot; null when history does not reach back that far
     * @param pctFromPivot  where the close sits relative to the pivot; negative is below
     */
    public record Base(LocalDate pivotDate, double pivot, int sessions, double depthPct,
                       Double priorAdvancePct, double pctFromPivot) {
    }

    /**
     * A close through a base's pivot on heavy volume.
     *
     * @param sessionsAgo   0 when it happened on the latest bar
     * @param pctFromPivot  where the latest close sits relative to that pivot
     * @param maxGainPct    the furthest the stock has run above the pivot since
     */
    public record Breakout(LocalDate date, double pivot, double volumeRatio, int sessionsAgo,
                           double pctFromPivot, double maxGainPct) {
    }

    /** The session the stock closed below its 50-day average, and the volume it did it on. */
    public record FiftyDayBreak(LocalDate date, Double volumeRatio) {
    }
}
