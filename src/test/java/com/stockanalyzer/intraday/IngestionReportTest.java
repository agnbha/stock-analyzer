package com.stockanalyzer.intraday;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionReportTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 27);

    private static IngestionReport.SymbolResult ok(String symbol) {
        return new IngestionReport.SymbolResult(symbol, 1, 375, Set.of(DAY), null);
    }

    private static IngestionReport.SymbolResult failed(String symbol) {
        return new IngestionReport.SymbolResult(symbol, 0, 0, Set.of(), "boom");
    }

    private static IngestionReport of(IngestionReport.SymbolResult... results) {
        return new IngestionReport(1, DAY, DAY, results.length, 0, List.of(results), List.of());
    }

    @Test
    @DisplayName("some symbols failing is routine, and self-heals on the next run")
    void partialFailureIsNotATotalFailure() {
        assertFalse(of(ok("RELIANCE"), failed("TCS")).totalFailure());
    }

    @Test
    @DisplayName("nothing succeeding means the setup is broken, not that the market was quiet")
    void nothingSucceedingIsATotalFailure() {
        assertTrue(of(failed("RELIANCE"), failed("TCS")).totalFailure());
    }

    @Test
    @DisplayName("having nothing to do is success, not failure")
    void anEmptyRunIsNotAFailure() {
        assertFalse(of().totalFailure(), "a run with nothing missing must not look broken");
    }

    @Test
    @DisplayName("a symbol that simply had no data did not fail")
    void noDataIsNotAnError() {
        IngestionReport report = of(new IngestionReport.SymbolResult("RELIANCE", 0, 0, Set.of(), null));

        assertFalse(report.totalFailure());
        assertTrue(report.failures().isEmpty());
    }

    @Test
    @DisplayName("a symbol that answers with no candles is reported, not counted as fine")
    void namesSymbolsThatReturnedNothingWhileOthersGotData() {
        IngestionReport report = new IngestionReport(1L, DAY, DAY, 3, 2,
                List.of(new IngestionReport.SymbolResult("RELIANCE", 1, 375, Set.of(DAY), null),
                        new IngestionReport.SymbolResult("TCS", 1, 375, Set.of(DAY), null),
                        // Renamed ticker: the provider said SUCCESS and sent nothing.
                        new IngestionReport.SymbolResult("TATAMOTORS", 0, 0, Set.of(), null)),
                List.of());

        assertEquals(0, report.failures().size(), "nothing errored, which is why this hid");
        assertEquals(3, report.succeeded());
        assertEquals(List.of("TATAMOTORS"),
                report.returnedNothing().stream().map(IngestionReport.SymbolResult::symbol).toList());
    }

    @Test
    @DisplayName("a day when nobody got data is a holiday, not a list of broken symbols")
    void staysQuietWhenNoSymbolGotData() {
        IngestionReport report = new IngestionReport(1L, DAY, DAY, 2, 0,
                List.of(new IngestionReport.SymbolResult("RELIANCE", 0, 0, Set.of(), null),
                        new IngestionReport.SymbolResult("TCS", 0, 0, Set.of(), null)),
                List.of(DAY));

        assertEquals(List.of(), report.returnedNothing());
    }
}
