package com.stockanalyzer.report;

import com.stockanalyzer.canslim.CanSlimReport;
import com.stockanalyzer.canslim.CanSlimScore;
import com.stockanalyzer.canslim.CanSlimSignal;
import com.stockanalyzer.canslim.MarketSummary;
import com.stockanalyzer.canslim.PriceAction;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Prints a scan: the market first, because it qualifies every line under it. */
public final class CanSlimReporter {

    private final PrintStream out;

    public CanSlimReporter(PrintStream out) {
        this.out = out;
    }

    /** @param all also list stocks with no signal, which is most of them */
    public void report(CanSlimReport report, boolean all) {
        report(report, all, 0);
    }

    /**
     * @param all also list stocks with no signal, which is most of them
     * @param top when positive, also list this many of the strongest stocks by RS rating
     */
    public void report(CanSlimReport report, boolean all, int top) {
        out.printf(Locale.ROOT, "%nCAN SLIM scan #%d%n", report.runId());
        out.printf(Locale.ROOT, "Market (M): %s - %s%n", report.market().state(), report.market().note());
        if (report.screenerNotice() != null) {
            out.printf(Locale.ROOT, "Screener%s: %s%n", report.screenerFailed() ? " FAILED" : "",
                    report.screenerNotice());
        }
        if (report.fundamentalsNotice() != null) {
            out.println(report.fundamentalsNotice());
        }

        Map<CanSlimScore.Source, Long> bySource = report.scores().stream()
                .collect(Collectors.groupingBy(CanSlimScore::source, Collectors.counting()));
        out.printf(Locale.ROOT, "Screener returned %d companies; scored %d symbols (%d screened, %d held, %d universe"
                        + ", %d market)%n",
                report.screened(), report.scores().size(),
                bySource.getOrDefault(CanSlimScore.Source.SCREENER, 0L),
                bySource.getOrDefault(CanSlimScore.Source.HOLDING, 0L),
                bySource.getOrDefault(CanSlimScore.Source.UNIVERSE, 0L),
                bySource.getOrDefault(CanSlimScore.Source.MARKET, 0L));

        if (report.marketSummary() != null) {
            printMarket(report.marketSummary());
        }

        List<CanSlimScore> shown = report.scores().stream()
                .filter(s -> all || s.signal() != CanSlimSignal.NONE)
                .toList();
        if (shown.isEmpty()) {
            out.println("\nNo signals today.");
        } else {
            out.printf(Locale.ROOT, "%n%-16s %-12s %-8s %-7s %3s %10s %10s %8s  %s%n",
                    "SIGNAL", "SYMBOL", "SOURCE", "CANSLIM", "RS", "CLOSE", "PIVOT", "VS PIVOT", "WHY");
            out.println("-".repeat(120));
            for (CanSlimScore s : shown) {
                PriceAction pa = s.priceAction();
                Double pivot = pivotOf(pa, PriceAction.Breakout::pivot, PriceAction.Base::pivot);
                Double fromPivot = pivotOf(pa, PriceAction.Breakout::pctFromPivot, PriceAction.Base::pctFromPivot);
                out.printf(Locale.ROOT, "%-16s %-12s %-8s %-7s %3s %10s %10s %8s  %s%n",
                        s.signal(), s.symbol(), s.source().name().toLowerCase(Locale.ROOT),
                        s.letters().display(),
                        s.rsRating() == null ? "-" : s.rsRating().toString(),
                        pa == null ? "-" : String.format(Locale.ROOT, "%.2f", pa.close()),
                        pivot == null ? "-" : String.format(Locale.ROOT, "%.2f", pivot),
                        fromPivot == null ? "-" : String.format(Locale.ROOT, "%+.1f%%", fromPivot),
                        s.reason());
            }
        }

        if (top > 0) {
            printStrongest(report.scores(), top);
        }

        if (!report.failures().isEmpty()) {
            out.printf(Locale.ROOT, "%n%d symbol(s) could not be priced:%n", report.failures().size());
            report.failures().forEach(f -> out.println("  " + f));
        }
        out.println();
        out.println("CANSLIM column: a letter that passed, '-' failed, '?' unknown. Signals are rule readings, "
                + "not advice.");
    }

    private void printMarket(MarketSummary summary) {
        out.printf(Locale.ROOT, "Market-wide: %d listed equities; %d ranked, %d could not be priced",
                summary.listed(), summary.ranked(), summary.failed());
        if (!summary.unranked().isEmpty()) {
            out.print("; left out of the ranking: " + summary.unranked().entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", ")));
        }
        out.printf(Locale.ROOT, "; %d had their company page read (RS high enough to be a buy)%n",
                summary.fundamentalsRequested());
    }

    /** Everything that has a rating, strongest first: the order is the point of the list. */
    private void printStrongest(List<CanSlimScore> scores, int top) {
        List<CanSlimScore> ranked = scores.stream()
                .filter(s -> s.rsRating() != null)
                .sorted(Comparator.comparing((CanSlimScore s) -> -s.rsRating())
                        .thenComparing(s -> -s.letters().passed())
                        .thenComparing(CanSlimScore::symbol))
                .limit(top)
                .toList();
        out.printf(Locale.ROOT, "%nStrongest by relative strength (top %d of %d rated)%n", ranked.size(),
                scores.stream().filter(s -> s.rsRating() != null).count());
        out.printf(Locale.ROOT, "%3s  %-12s %-8s %-7s %3s %10s %9s  %-16s %s%n",
                "#", "SYMBOL", "SOURCE", "CANSLIM", "RS", "CLOSE", "BELOW HI", "SIGNAL", "WHY");
        out.println("-".repeat(120));
        int rank = 0;
        for (CanSlimScore s : ranked) {
            PriceAction pa = s.priceAction();
            out.printf(Locale.ROOT, "%3d  %-12s %-8s %-7s %3d %10.2f %8.1f%%  %-16s %s%n",
                    ++rank, s.symbol(), s.source().name().toLowerCase(Locale.ROOT), s.letters().display(),
                    s.rsRating(), pa == null ? 0.0 : pa.close(),
                    pa == null || pa.pctFromHigh() == null ? 0.0 : pa.pctFromHigh(),
                    s.signal(), s.reason());
        }
    }

    private static Double pivotOf(PriceAction pa, Function<PriceAction.Breakout, Double> fromBreakout,
                                  Function<PriceAction.Base, Double> fromBase) {
        if (pa == null) {
            return null;
        }
        if (pa.breakout() != null) {
            return fromBreakout.apply(pa.breakout());
        }
        return pa.base() == null ? null : fromBase.apply(pa.base());
    }
}
