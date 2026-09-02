package com.stockanalyzer.live;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.model.LiveSnapshot;
import com.stockanalyzer.model.LiveSymbolState;
import com.stockanalyzer.util.MarketClock;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One row per symbol, sorted by today's move.
 *
 * <p>Staleness is shown rather than hidden, and anything projected is labelled
 * as such: a stale or modelled number presented as live is worse than no number.
 */
public final class TerminalLiveView implements LiveView {

    private static final String ESCAPE = String.valueOf((char) 27);
    private static final String CLEAR_SCREEN = ESCAPE + "[2J" + ESCAPE + "[H";

    private final MarketClock clock;
    private final boolean ansi;

    public TerminalLiveView(MarketClock clock, boolean ansi) {
        this.clock = clock;
        // Redirected to a file there is no screen to clear, and the escape codes
        // turn a log into something you have to decode. A file gets an
        // append-only record; a terminal gets the live redraw.
        this.ansi = ansi && System.console() != null;
    }

    @Override
    public void render(LiveSnapshot snapshot) {
        StringBuilder out = new StringBuilder();
        if (ansi) {
            out.append(CLEAR_SCREEN);
        } else {
            // No redraw, so each frame needs its own separator to be findable.
            out.append(System.lineSeparator()).append("-".repeat(104))
               .append(System.lineSeparator());
        }
        String freshness = snapshot.degraded()
                ? "DELAYED (" + snapshot.degradedReason() + ")"
                : "live (" + snapshot.stalenessSeconds() + "s)";
        out.append(String.format(Locale.ROOT, "NSE - %s %s IST - %s - next poll %s - %d symbols%n%n",
                snapshot.sessionDate(), clock.timeOf(snapshot.asOfEpoch()).withNano(0), freshness,
                clock.timeOf(snapshot.nextPollEpoch()).withNano(0), snapshot.symbols().size()));

        out.append(String.format(Locale.ROOT, "%-10s %10s %8s %6s  %-32s %13s  %s%n",
                "SYMBOL", "LAST", "DAY%", "VOLx", "TOP-3 SO FAR", "PROJ", "SIGNAL"));
        out.append("-".repeat(104)).append(System.lineSeparator());

        List<LiveSymbolState> sorted = snapshot.symbols().stream()
                .sorted(Comparator.comparingDouble(
                        (LiveSymbolState s) -> s.dayChangePct() == null ? 0 : s.dayChangePct()).reversed())
                .toList();

        for (LiveSymbolState state : sorted) {
            out.append(String.format(Locale.ROOT, "%-10s %10.2f %8s %6.1f  %-32s %13s  %s%n",
                    state.symbol(),
                    state.lastPrice(),
                    state.dayChangePct() == null ? "-" : String.format(Locale.ROOT, "%+.2f", state.dayChangePct()),
                    state.volumeRatio(),
                    formatOpportunities(state.topSoFar()),
                    state.projectedReturnPct() == null
                            ? "-"
                            : String.format(Locale.ROOT, "%+.2f%% proj", state.projectedReturnPct()),
                    formatSignal(state)));
        }

        if (!snapshot.recentAlerts().isEmpty()) {
            out.append(System.lineSeparator());
            for (Alert alert : snapshot.recentAlerts()) {
                out.append(String.format(Locale.ROOT, "  %s  %s%n",
                        clock.timeOf(alert.firedAtEpoch()).withNano(0), alert.message()));
            }
        }

        long forming = snapshot.symbols().stream().filter(LiveSymbolState::lastCandleProvisional).count();
        if (forming > 0) {
            out.append(String.format(Locale.ROOT,
                    "%n  %d symbols have a still-forming candle: shown, never stored.%n", forming));
        }
        System.out.print(out);
        System.out.flush();
    }

    private String formatOpportunities(List<GainOpportunity> opportunities) {
        if (opportunities == null || opportunities.isEmpty()) {
            return "-";
        }
        return opportunities.stream()
                .limit(2)
                .map(o -> String.format(Locale.ROOT, "%s>%s %+.2f",
                        clock.timeOf(o.entryTs()).withSecond(0).withNano(0),
                        clock.timeOf(o.exitTs()).withSecond(0).withNano(0),
                        o.gainPct()))
                .reduce((a, b) -> a + "  " + b)
                .orElse("-");
    }

    private static String formatSignal(LiveSymbolState state) {
        if (state.latestSignal() == null) {
            return "-";
        }
        return String.format(Locale.ROOT, "%s %.2f",
                state.latestSignal().signal(), state.latestSignal().probability());
    }
}
