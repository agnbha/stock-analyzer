package com.stockanalyzer.canslim;

import java.time.YearMonth;
import java.util.Map;

/**
 * What a company page yielded: the ratios under screener.in's names, and the
 * latest quarter each of the two quarterly tables showed. The quarters are what
 * lets a cached copy be judged stale by the calendar rather than by its age: a
 * company's figures change when it reports a new quarter, not on a schedule.
 *
 * @param resultsQuarter the newest column of the quarterly results table, or null when there is none
 * @param holdingQuarter the newest column of the shareholding table, or null when there is none
 */
public record CompanyFundamentals(Map<String, Double> ratios, YearMonth resultsQuarter, YearMonth holdingQuarter) {

    /** A page with no figures on it: an ETF, or a symbol screener.in does not carry. */
    public static final CompanyFundamentals NONE = new CompanyFundamentals(Map.of(), null, null);

    public CompanyFundamentals {
        ratios = Map.copyOf(ratios);
    }

    public boolean isEmpty() {
        return ratios.isEmpty();
    }
}
