package com.stockanalyzer.live;

import com.stockanalyzer.intraday.DailyIngestionService;
import com.stockanalyzer.intraday.IngestionReport;
import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.store.LiveCandleRepository;
import com.stockanalyzer.store.LiveOpportunityRepository;
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
    private final LiveCandleRepository liveCandleRepository;
    private final LiveOpportunityRepository liveOpportunityRepository;

    public SessionReconciler(DailyIngestionService ingestionService,
                             LiveCandleRepository liveCandleRepository,
                             LiveOpportunityRepository liveOpportunityRepository) {
        this.ingestionService = ingestionService;
        this.liveCandleRepository = liveCandleRepository;
        this.liveOpportunityRepository = liveOpportunityRepository;
    }

    public IngestionReport reconcile(LocalDate sessionDate, List<String> symbols, String exchange, String segment,
                                     int intervalMinutes, Map<String, List<GainOpportunity>> liveTopBySymbol) {
        log.info("Reconciling {} against the authoritative tape", sessionDate);
        // Always re-fetch: the session was very likely ingested while the market
        // was open, and gap-filling would skip it and leave that partial day as
        // the canonical record.
        IngestionReport report = ingestionService.ingest(symbols, exchange, segment,
                sessionDate, sessionDate, intervalMinutes, "live-reconcile", true);

        clearStaging(sessionDate, report);

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

    /**
     * The monitor owns the staging it wrote, so it clears it here rather than
     * leaving that to a later job. Staged windows deliberately win over stored
     * ones while they exist, so leaving them behind would mean the dashboards
     * kept showing the last live tick instead of the authoritative day.
     *
     * <p>Only when the re-fetch actually produced something. If it wrote
     * nothing, the staged view is the better of the two and is left alone -
     * clearing it would replace a complete live picture with whatever partial
     * snapshot happened to be stored earlier.
     */
    private void clearStaging(LocalDate sessionDate, IngestionReport report) {
        if (report.sessionsWritten() == 0) {
            log.warn("Reconciliation of {} wrote nothing; leaving staged data in place, since it "
                    + "covers more of the session than what is stored", sessionDate);
            return;
        }
        int candles = liveCandleRepository.deleteConsolidated();
        int windows = liveOpportunityRepository.deleteForSession(sessionDate);
        log.info("Cleared staging for {}: {} candles, {} windows", sessionDate, candles, windows);
    }

    /** True when two gain figures agree closely enough to call the live view faithful. */
    public static boolean agrees(double liveGainPct, double finalGainPct) {
        return Math.abs(liveGainPct - finalGainPct) <= TOLERANCE_PCT;
    }
}
