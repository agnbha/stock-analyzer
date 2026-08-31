package com.stockanalyzer.intraday;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** What one ingestion pass did, per symbol, so a partial run is legible afterwards. */
public record IngestionReport(long runId,
                              LocalDate from,
                              LocalDate to,
                              int symbolsRequested,
                              int sessionsWritten,
                              List<SymbolResult> results,
                              List<LocalDate> inferredHolidays) {

    public List<SymbolResult> failures() {
        return results.stream().filter(r -> r.error() != null).toList();
    }

    public int succeeded() {
        return (int) results.stream().filter(r -> r.error() == null).count();
    }

    /**
     * Every symbol that was attempted failed.
     *
     * <p>Worth separating from a partial failure: some symbols failing is
     * routine and self-heals, because the next run simply finds them still
     * missing. Nothing succeeding means credentials, connectivity or an
     * entitlement is broken, and a scheduled job should exit non-zero so it is
     * actually noticed rather than logged into the void.
     */
    public boolean totalFailure() {
        return !results.isEmpty() && succeeded() == 0;
    }

    public record SymbolResult(String symbol,
                               int sessionsWritten,
                               int candlesWritten,
                               Set<LocalDate> datesWithData,
                               String error) {
    }
}
