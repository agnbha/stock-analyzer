package com.stockanalyzer.report;

import com.stockanalyzer.canslim.CanSlimReport;
import com.stockanalyzer.canslim.CanSlimScore;
import com.stockanalyzer.canslim.CanSlimSignal;
import com.stockanalyzer.canslim.PriceAction;

import java.io.PrintStream;
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
        out.printf(Locale.ROOT, "Screener returned %d companies; scored %d symbols (%d screened, %d held, %d universe)%n",
                report.screened(), report.scores().size(),
                bySource.getOrDefault(CanSlimScore.Source.SCREENER, 0L),
                bySource.getOrDefault(CanSlimScore.Source.HOLDING, 0L),
                bySource.getOrDefault(CanSlimScore.Source.UNIVERSE, 0L));

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

        if (!report.failures().isEmpty()) {
            out.printf(Locale.ROOT, "%n%d symbol(s) could not be priced:%n", report.failures().size());
            report.failures().forEach(f -> out.println("  " + f));
        }
        out.println();
        out.println("CANSLIM column: a letter that passed, '-' failed, '?' unknown. Signals are rule readings, "
                + "not advice.");
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
