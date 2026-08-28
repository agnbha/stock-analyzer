package com.stockanalyzer.report;

import com.stockanalyzer.intraday.IngestionReport;
import com.stockanalyzer.store.OpportunityRow;
import com.stockanalyzer.util.MarketClock;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Prints one session's top-3 windows per symbol, plus what the run did. */
public final class DailyOpportunityReporter {

    private final MarketClock clock;

    public DailyOpportunityReporter(MarketClock clock) {
        this.clock = clock;
    }

    public void report(LocalDate sessionDate, IngestionReport ingestion, List<OpportunityRow> opportunities) {
        System.out.printf("%nSession %s - %d symbols, %d sessions written, %d failed%n",
                sessionDate, ingestion.symbolsRequested(), ingestion.sessionsWritten(),
                ingestion.failures().size());
        if (!ingestion.inferredHolidays().isEmpty()) {
            System.out.println("Recorded as non-trading: " + ingestion.inferredHolidays());
        }
        System.out.println();

        if (opportunities.isEmpty()) {
            System.out.println("No gain opportunities stored for this session.");
        } else {
            System.out.printf("%-12s %-4s %-8s %-8s %9s %7s%n", "SYMBOL", "#", "ENTRY", "EXIT", "GAIN%", "HELD");
            System.out.println("-".repeat(54));
            Map<String, List<OpportunityRow>> bySymbol = opportunities.stream()
                    .collect(Collectors.groupingBy(OpportunityRow::symbol, java.util.TreeMap::new,
                            Collectors.toList()));
            bySymbol.forEach((symbol, rows) -> {
                boolean first = true;
                for (OpportunityRow row : rows) {
                    System.out.printf(Locale.ROOT, "%-12s %-4d %-8s %-8s %+9.2f %6dm%n",
                            first ? symbol : "",
                            row.opportunity().rank(),
                            clock.timeOf(row.opportunity().entryTs()).withSecond(0).withNano(0),
                            clock.timeOf(row.opportunity().exitTs()).withSecond(0).withNano(0),
                            row.opportunity().gainPct(),
                            row.opportunity().durationMinutes());
                    first = false;
                }
            });
        }

        if (!ingestion.failures().isEmpty()) {
            System.out.println();
            System.out.println("Failed:");
            ingestion.failures().forEach(f -> System.out.printf("  %-12s %s%n", f.symbol(), f.error()));
        }
        System.out.println();
    }
}
