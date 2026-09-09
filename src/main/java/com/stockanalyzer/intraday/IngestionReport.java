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

    /**
     * Symbols the provider accepted and answered, with nothing in the answer.
     *
     * <p>Not a failure and not a success: a renamed or delisted ticker still
     * resolves, returns {@code SUCCESS} and no candles, and is counted among
     * the symbols that "succeeded". That is how a symbol can quietly stop being
     * collected for months while every run reports zero failures. It is only
     * meaningful when other symbols did get data - a day when nobody did is a
     * holiday, which is recognised separately.
     */
    public List<SymbolResult> returnedNothing() {
        boolean anyoneGotData = results.stream().anyMatch(r -> r.sessionsWritten() > 0);
        if (!anyoneGotData) {
            return List.of();
        }
        return results.stream()
                .filter(r -> r.error() == null && r.sessionsWritten() == 0)
                .toList();
    }

    public record SymbolResult(String symbol,
                               int sessionsWritten,
                               int candlesWritten,
                               Set<LocalDate> datesWithData,
                               String error) {
    }
}
