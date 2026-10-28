package com.stockanalyzer.canslim;

import java.util.Map;

/**
 * One row of a screener.in result: the NSE symbol, the display name, and every
 * numeric column the result carried, keyed by screener's full ratio name (the
 * column header's tooltip, e.g. {@code "YOY Quarterly profit growth"}).
 *
 * <p>Which ratios are present depends on the query: screener appends a column
 * for every ratio the query mentions. A ratio that is absent is unknown, not
 * zero - see {@link #ratio(String)}.
 */
public record ScreenedStock(String symbol, String name, Map<String, Double> ratios) {

    public ScreenedStock {
        ratios = Map.copyOf(ratios);
    }

    /** The named ratio, or null when the result did not carry it. */
    public Double ratio(String name) {
        return ratios.get(name);
    }
}
