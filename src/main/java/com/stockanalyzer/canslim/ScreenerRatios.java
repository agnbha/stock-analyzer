package com.stockanalyzer.canslim;

/**
 * screener.in's names for the ratios the fundamental letters read. They are
 * the column tooltips in a result, and the words a query uses, so the default
 * query mentions each of them - which is also what makes screener return them
 * as columns. A custom query that leaves one out leaves that letter unknown.
 */
public final class ScreenerRatios {

    /** C: latest quarter's net profit against the same quarter last year, %. */
    public static final String QTR_PROFIT_GROWTH = "YOY Quarterly profit growth";
    /** C: the same for sales. */
    public static final String QTR_SALES_GROWTH = "YOY Quarterly sales growth";
    /** A: compounded annual profit growth over three years, %. */
    public static final String PROFIT_GROWTH_3Y = "Profit growth 3Years";
    /** A: return on equity, %. */
    public static final String ROE = "Return on equity";
    /** I: change in foreign institutional holding over the last quarter, percentage points. */
    public static final String FII_CHANGE = "Change in FII holding";
    /** I: the same for domestic institutions. */
    public static final String DII_CHANGE = "Change in DII holding";

    private ScreenerRatios() {
    }
}
