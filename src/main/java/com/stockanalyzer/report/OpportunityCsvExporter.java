package com.stockanalyzer.report;

import com.stockanalyzer.store.OpportunityRow;
import com.stockanalyzer.util.MarketClock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Writes the accumulated opportunities out for a spreadsheet. */
public final class OpportunityCsvExporter {

    private final MarketClock clock;

    public OpportunityCsvExporter(MarketClock clock) {
        this.clock = clock;
    }

    public void export(List<OpportunityRow> rows, Path target) {
        StringBuilder csv = new StringBuilder(
                "session_date,symbol,rank,entry_time,exit_time,entry_price,exit_price,gain_pct,duration_minutes\n");
        for (OpportunityRow row : rows) {
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%s,%s,%.2f,%.2f,%.4f,%d%n",
                    row.sessionDate(), row.symbol(), row.opportunity().rank(),
                    clock.timeOf(row.opportunity().entryTs()).withSecond(0).withNano(0),
                    clock.timeOf(row.opportunity().exitTs()).withSecond(0).withNano(0),
                    row.opportunity().entryPrice(), row.opportunity().exitPrice(),
                    row.opportunity().gainPct(), row.opportunity().durationMinutes()));
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, csv.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
        System.out.printf("Wrote %d rows to %s%n", rows.size(), target);
    }
}
