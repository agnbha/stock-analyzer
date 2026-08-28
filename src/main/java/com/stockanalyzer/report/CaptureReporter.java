package com.stockanalyzer.report;

import com.stockanalyzer.model.TradeAttribution;

import java.util.List;
import java.util.Locale;

/**
 * How much of what was available you actually took, and how late you were.
 * Aggregated over months this tracks skill; P&L alone mostly tracks the market.
 */
public final class CaptureReporter {

    public void report(String periodLabel, List<TradeAttribution> attributions) {
        System.out.printf("%nCapture - %s - %d entries matched to an available window%n%n",
                periodLabel, attributions.size());
        if (attributions.isEmpty()) {
            System.out.println("No buys in this period matched a stored opportunity. "
                    + "Run 'backfill' for these dates first, then re-run capture.");
            return;
        }

        double meanCapture = attributions.stream()
                .filter(a -> a.capturePct() != null)
                .mapToDouble(TradeAttribution::capturePct)
                .average().orElse(0);
        double meanLag = attributions.stream()
                .filter(a -> a.entryLagMinutes() != null)
                .mapToInt(TradeAttribution::entryLagMinutes)
                .average().orElse(0);
        long early = attributions.stream()
                .filter(a -> a.entryLagMinutes() != null && a.entryLagMinutes() < 0)
                .count();

        System.out.printf(Locale.ROOT, "  Mean capture ratio   %6.1f%%   (your window's gain vs the day's best)%n",
                meanCapture);
        System.out.printf(Locale.ROOT, "  Mean entry lag       %+6.1f min (negative means you were early)%n",
                meanLag);
        System.out.printf(Locale.ROOT, "  Entries before the ideal moment: %d of %d%n%n",
                early, attributions.size());
    }
}
