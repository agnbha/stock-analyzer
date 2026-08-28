package com.stockanalyzer.store;

import com.stockanalyzer.model.GainOpportunity;

import java.time.LocalDate;
import java.util.List;

public interface GainOpportunityRepository {

    /**
     * Replaces the opportunities for one session and detector version. Keyed by
     * detector version so re-running an improved detector writes a parallel
     * series instead of overwriting the old one.
     */
    void replace(long tradingDayId, String detectorVersion, List<GainOpportunity> opportunities);

    List<GainOpportunity> findByTradingDay(long tradingDayId, String detectorVersion);

    /** All opportunities in a date range, newest session first, for reporting and export. */
    List<OpportunityRow> findRange(LocalDate from, LocalDate to, String detectorVersion, String symbolOrNull);

    /** Entry timestamps in a range, used to build the time-of-day prior. */
    List<OpportunityRow> findForHotWindows(LocalDate from, LocalDate to, String detectorVersion);
}
