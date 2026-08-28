package com.stockanalyzer.report;

import com.stockanalyzer.model.OpenPosition;
import com.stockanalyzer.model.PnlSummary;

import java.util.List;
import java.util.Locale;

/** The gain/loss statement, gross and net side by side. */
public final class ConsolePnlReporter {

    public void report(String periodLabel, PnlSummary summary, List<PnlSummary> breakdown,
                       List<OpenPosition> openPositions) {
        System.out.printf("%nP&L - %s %s - %s - net of charges%n%n",
                summary.periodType(), periodLabel,
                summary.symbol() == null ? "all symbols" : summary.symbol());

        System.out.printf(Locale.ROOT, "  Realized (gross) %14s      Turnover %14s%n",
                money(summary.grossPnl()), money(summary.turnover()));
        System.out.printf(Locale.ROOT, "  Charges          %14s      (%.3f%% of turnover)%n",
                money(-summary.charges()), summary.chargesPctTurnover());
        System.out.printf(Locale.ROOT, "  Net realized     %14s%n", money(summary.netPnl()));
        System.out.printf(Locale.ROOT, "  Unrealized       %14s      (%d open position%s)%n%n",
                money(summary.unrealizedEnd()), openPositions.size(),
                openPositions.size() == 1 ? "" : "s");

        System.out.printf(Locale.ROOT, "  Closed lots %3d   Wins %d (%.1f%%)   Avg win %s   Profit factor %s%n",
                summary.closedLots(), summary.wins(), summary.winRate(), money(summary.avgWin()),
                Double.isInfinite(summary.profitFactor()) ? "inf"
                        : String.format(Locale.ROOT, "%.2f", summary.profitFactor()));
        System.out.printf(Locale.ROOT, "  Trades      %3d   Losses %d          Avg loss %s%n",
                summary.trades(), summary.losses(), money(summary.avgLoss()));

        if (!breakdown.isEmpty()) {
            System.out.printf("%n  %-14s %14s %8s %8s%n", "PERIOD", "NET", "LOTS", "WIN%");
            System.out.println("  " + "-".repeat(48));
            for (PnlSummary part : breakdown) {
                if (part.closedLots() == 0 && part.trades() == 0) {
                    continue;
                }
                System.out.printf(Locale.ROOT, "  %-14s %14s %8d %8.1f%n",
                        part.periodStart(), money(part.netPnl()), part.closedLots(), part.winRate());
            }
        }

        if (!openPositions.isEmpty()) {
            System.out.printf("%n  Open positions:%n");
            for (OpenPosition position : openPositions) {
                System.out.printf(Locale.ROOT, "    %-12s %-4s %6d @ %.2f%n",
                        position.symbol(), position.product(), position.quantity(), position.avgCost());
            }
        }
        System.out.println();
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%+,.2f", value);
    }
}
