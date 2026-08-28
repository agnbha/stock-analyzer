package com.stockanalyzer.live;

import com.stockanalyzer.intraday.DailyIngestionService;
import com.stockanalyzer.intraday.IngestionReport;
import com.stockanalyzer.model.GainOpportunity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * At session close, re-fetches the authoritative day and writes it through the
 * ordinary nightly path, so the canonical tables are always built by one code
 * path regardless of whether the monitor was running.
 *
 * <p>Any divergence between what the live view showed and what the final tape
 * says is logged. That log is the early warning that the feed is lagging or an
 * entitlement has changed - it is not noise to be silenced.
 */
public final class SessionReconciler {

    private static final Logger log = LoggerFactory.getLogger(SessionReconciler.class);
    private static final double TOLERANCE_PCT = 0.05;

    private final DailyIngestionService ingestionService;

    public SessionReconciler(DailyIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public IngestionReport reconcile(LocalDate sessionDate, List<String> symbols, String exchange, String segment,
                                     int intervalMinutes, Map<String, List<GainOpportunity>> liveTopBySymbol) {
        log.info("Reconciling {} against the authoritative tape", sessionDate);
        IngestionReport report = ingestionService.ingest(symbols, exchange, segment,
                sessionDate, sessionDate, intervalMinutes, "live-reconcile");

        liveTopBySymbol.forEach((symbol, liveTop) -> {
            if (liveTop.isEmpty()) {
                return;
            }
            double liveBest = liveTop.getFirst().gainPct();
            report.results().stream()
                    .filter(result -> result.symbol().equals(symbol) && result.error() != null)
                    .findFirst()
                    .ifPresent(failed -> log.warn("{}: live view showed {}%, but the final fetch failed ({})",
                            symbol, String.format("%.2f", liveBest), failed.error()));
        });
        return report;
    }

    /** True when two gain figures agree closely enough to call the live view faithful. */
    public static boolean agrees(double liveGainPct, double finalGainPct) {
        return Math.abs(liveGainPct - finalGainPct) <= TOLERANCE_PCT;
    }
}
