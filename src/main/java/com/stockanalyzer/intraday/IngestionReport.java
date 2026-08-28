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

    public record SymbolResult(String symbol,
                               int sessionsWritten,
                               int candlesWritten,
                               Set<LocalDate> datesWithData,
                               String error) {
    }
}
