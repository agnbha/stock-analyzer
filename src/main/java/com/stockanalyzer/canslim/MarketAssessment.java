package com.stockanalyzer.canslim;

import java.time.LocalDate;

/** The market's direction as of one session, and the readings it was decided from. */
public record MarketAssessment(String indexSymbol, LocalDate asOf, MarketState state, Double close,
                               Double sma50, Double sma200, int distributionDays, String note) {

    public static MarketAssessment unknown(String indexSymbol, String note) {
        return new MarketAssessment(indexSymbol, null, MarketState.UNKNOWN, null, null, null, 0, note);
    }
}
