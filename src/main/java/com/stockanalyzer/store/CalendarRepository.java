package com.stockanalyzer.store;

import java.time.LocalDate;
import java.util.Set;

public interface CalendarRepository {

    void markNonTrading(LocalDate date, String reason);

    Set<LocalDate> nonTradingDays(LocalDate from, LocalDate to);

    /** Instruments with a daily bar on a date before the bars count as proof that the market traded. */
    int MIN_DAILY_BARS_AS_EVIDENCE = 10;

    /**
     * The dates in the range on which the market evidently traded: a session is stored for any symbol, or
     * at least {@link #MIN_DAILY_BARS_AS_EVIDENCE} instruments have a daily bar. A holiday has neither, so a
     * date in this set must never be recorded as non-trading, whichever symbols a given run asked about.
     */
    Set<LocalDate> datesWithTradingEvidence(LocalDate from, LocalDate to);
}
