package com.stockanalyzer.canslim;

import java.util.List;

/**
 * What one scan found.
 *
 * @param screened       how many companies the screener query returned
 * @param failures       one line per symbol whose prices could not be fetched
 * @param screenerNotice why no screener query ran or why it failed, or null when it ran fine. A scan that
 *                       could not use the query still scores holdings and the universe, so this is a note
 *                       on the result, not the end of it.
 * @param screenerFailed true when a session was configured and the query was attempted and failed - the
 *                       case a person has to act on - as opposed to simply not having a session
 * @param needsLogin     true when the failure is an expired or rejected session
 * @param fundamentalsNotice how the C, A and I letters were sourced for the stocks the query did not return
 * @param marketSummary  the whole-market part of the scan, or null when it was not run
 */
public record CanSlimReport(long runId, String query, int screened, MarketAssessment market,
                            List<CanSlimScore> scores, List<String> failures, String screenerNotice,
                            boolean screenerFailed, boolean needsLogin, String fundamentalsNotice,
                            MarketSummary marketSummary) {
}
