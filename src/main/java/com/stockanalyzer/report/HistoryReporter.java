package com.stockanalyzer.report;

import com.stockanalyzer.store.OpportunityRow;
import com.stockanalyzer.util.MarketClock;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads back the accumulated months: per-session windows, and where in the day they cluster. */
public final class HistoryReporter {

    private final MarketClock clock;

    public HistoryReporter(MarketClock clock) {
        this.clock = clock;
    }

    public void report(String symbol, LocalDate from, LocalDate to, List<OpportunityRow> rows) {
        System.out.printf("%n%s - top windows from %s to %s (%d sessions, %d windows)%n%n",
                symbol == null ? "All symbols" : symbol, from, to,
                rows.stream().map(OpportunityRow::sessionDate).distinct().count(), rows.size());

        if (rows.isEmpty()) {
            System.out.println("Nothing stored for this range yet. Run 'backfill' first.");
            return;
        }

        System.out.println("Biggest single windows:");
        System.out.printf("%-12s %-12s %-8s %-8s %9s%n", "DATE", "SYMBOL", "ENTRY", "EXIT", "GAIN%");
        System.out.println("-".repeat(54));
        rows.stream()
                .sorted(Comparator.comparingDouble((OpportunityRow r) -> r.opportunity().gainPct()).reversed())
                .limit(10)
                .forEach(row -> System.out.printf(Locale.ROOT, "%-12s %-12s %-8s %-8s %+9.2f%n",
                        row.sessionDate(), row.symbol(),
                        clock.timeOf(row.opportunity().entryTs()).withSecond(0).withNano(0),
                        clock.timeOf(row.opportunity().exitTs()).withSecond(0).withNano(0),
                        row.opportunity().gainPct()));

        Map<Integer, List<OpportunityRow>> byHour = rows.stream()
                .collect(Collectors.groupingBy(row -> clock.timeOf(row.opportunity().entryTs()).getHour(),
                        java.util.TreeMap::new, Collectors.toList()));
        System.out.printf("%nWhen entries happened:%n");
        byHour.forEach((hour, hourRows) -> {
            double meanGain = hourRows.stream().mapToDouble(r -> r.opportunity().gainPct()).average().orElse(0);
            System.out.printf(Locale.ROOT, "  %02d:00  %-40s %4d windows, mean %+.2f%%%n",
                    hour, "#".repeat(Math.min(hourRows.size(), 40)), hourRows.size(), meanGain);
        });
        System.out.println();
    }
}
