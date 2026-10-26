package com.stockanalyzer.canslim;

import java.util.List;

/**
 * What one scan found.
 *
 * @param screened how many companies the screener query returned
 * @param failures one line per symbol whose prices could not be fetched
 */
public record CanSlimReport(long runId, String query, int screened, MarketAssessment market,
                            List<CanSlimScore> scores, List<String> failures) {
}
