package com.stockanalyzer.store;

import com.stockanalyzer.canslim.ConcallSummary;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** screener.in's AI summaries of earnings calls, read on request, one per company and call. */
public interface ConcallSummaryRepository {

    /** Replaces whatever is held for the company's call with these lines. */
    void save(long instrumentId, YearMonth call, String sourceUrl, List<ConcallSummary.Line> lines, long fetchedAtEpoch);

    /** When the call's summary was read, if it is held. */
    Optional<Long> fetchedAt(long instrumentId, YearMonth call);
}
