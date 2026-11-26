package com.stockanalyzer.canslim;

import java.time.LocalDate;

/**
 * A base to draw on a stock's chart: a box from the bar of the pivot to the day the base ended, and from the pivot down
 * to the lowest low inside it.
 *
 * @param kind      {@link Kind#BREAKOUT}: a base a breakout cleared, ending on the breakout day;
 *                  {@link Kind#CURRENT}: the base the stock is in now, ending on its latest bar
 * @param pivotDate the bar of the base's high, where the box starts
 * @param endDate   where it ends
 * @param pivot     the base's high, the top of the box
 * @param low       the lowest low from the pivot's bar to the session before the end, the bottom of the box: the depth
 *                  the scan reports for the base
 * @param sessions  how many bars the box spans, the pivot's and the last included
 */
public record BaseMark(Kind kind, LocalDate pivotDate, LocalDate endDate, double pivot, double low, int sessions) {

    public enum Kind {
        BREAKOUT, CURRENT
    }

    /** How far the lowest low sits below the pivot, as a positive %. */
    public double depthPct() {
        return (pivot - low) / pivot * 100.0;
    }
}
