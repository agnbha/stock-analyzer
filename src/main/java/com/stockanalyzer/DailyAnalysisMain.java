package com.stockanalyzer;

import com.stockanalyzer.config.AppConfig;
import com.stockanalyzer.intraday.IngestionReport;
import com.stockanalyzer.model.HotWindow;
import com.stockanalyzer.report.DailyOpportunityReporter;
import com.stockanalyzer.report.HistoryReporter;
import com.stockanalyzer.report.OpportunityCsvExporter;
import com.stockanalyzer.store.OpportunityRow;
import com.stockanalyzer.util.Args;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Part 1 entry point: fetch and analyse sessions, read the accumulated history
 * back, and maintain the time-of-day prior that Part 3 alerts fire from.
 *
 * <pre>
 *   daily [--date YYYY-MM-DD]        yesterday's session, or a specific one
 *   backfill --from D --to D         seed the previous months
 *   ... --force                      re-fetch days already stored, for a session
 *                                    that was ingested while the market was open
 *   recompute --from D [--to D]      re-run the detector on stored candles, no API calls
 *   report [--symbol S] [--months N] read the accumulated history back
 *   export --from D --to D --out F   write the opportunities to CSV
 *   hot-windows [--lookback N]       recompute the time-of-day prior
 *   evaluate [--date D]              score yesterday's predictions against what happened
 * </pre>
 */
public final class DailyAnalysisMain {

    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        String command = parsed.command("daily");

        try (AppContext context = new AppContext(AppConfig.load())) {
            switch (command) {
                case "daily" -> daily(context, parsed);
                case "backfill" -> backfill(context, parsed);
                case "recompute" -> recompute(context, parsed);
                case "report" -> report(context, parsed);
                case "export" -> export(context, parsed);
                case "hot-windows" -> hotWindows(context, parsed);
                case "evaluate" -> evaluate(context, parsed);
                default -> {
                    System.err.println("Unknown command: " + command);
                    System.err.println("Try: daily | backfill | recompute | report | export "
                            + "| hot-windows | evaluate");
                    System.exit(2);
                }
            }
        }
    }

    private static void daily(AppContext context, Args args) {
        LocalDate sessionDate = args.date("date",
                context.tradingCalendar().previousTradingDay(LocalDate.now(context.clock().zone()).plusDays(1)));
        AppConfig config = context.config();

        IngestionReport report = context.ingestionService().ingest(config.stockSymbols(), config.exchange(),
                config.segment(), sessionDate, sessionDate, config.intradayIntervalMinutes(), "daily",
                args.flag("force"));

        List<OpportunityRow> opportunities = context.gainOpportunityRepository()
                .findRange(sessionDate, sessionDate, context.detector().version(), null);
        new DailyOpportunityReporter(context.clock()).report(sessionDate, report, opportunities);
        exitNonZeroOnTotalFailure(report);
    }

    private static void backfill(AppContext context, Args args) {
        LocalDate from = LocalDate.parse(args.require("from"));
        LocalDate to = args.date("to", LocalDate.now(context.clock().zone()).minusDays(1));
        AppConfig config = context.config();

        System.out.printf("Backfilling %s to %s for %d symbols%n", from, to, config.stockSymbols().size());
        IngestionReport report = context.ingestionService().ingest(config.stockSymbols(), config.exchange(),
                config.segment(), from, to, config.intradayIntervalMinutes(), "backfill",
                args.flag("force"));

        System.out.printf("Wrote %d sessions across %d symbols; %d failed%n",
                report.sessionsWritten(), report.succeeded(), report.failures().size());
        report.failures().forEach(f -> System.out.printf("  %-12s %s%n", f.symbol(), f.error()));
        exitNonZeroOnTotalFailure(report);
    }

    /**
     * A scheduled run has to be able to tell a bad night from a broken setup.
     * Some symbols failing is routine and self-heals; nothing succeeding is a
     * problem that needs a person, so it leaves a non-zero status behind.
     */
    private static void exitNonZeroOnTotalFailure(IngestionReport report) {
        if (!report.totalFailure()) {
            return;
        }
        System.err.printf("%nEvery one of the %d symbols failed. This is not a quiet market day - "
                + "check credentials, connectivity and API entitlements.%n", report.symbolsRequested());
        report.failures().stream().findFirst()
                .ifPresent(first -> System.err.println("First error: " + first.error()));
        System.exit(1);
    }

    private static void recompute(AppContext context, Args args) {
        LocalDate from = LocalDate.parse(args.require("from"));
        LocalDate to = args.date("to", LocalDate.now(context.clock().zone()));
        int recomputed = context.recomputeService()
                .recompute(from, to, context.config().intradayIntervalMinutes());
        System.out.printf("Recomputed %d sessions as %s%n", recomputed, context.detector().version());
    }

    private static void report(AppContext context, Args args) {
        String symbol = args.value("symbol").orElse(null);
        int months = args.integer("months", 6);
        LocalDate to = LocalDate.now(context.clock().zone());
        LocalDate from = to.minusMonths(months);

        List<OpportunityRow> rows = context.gainOpportunityRepository()
                .findRange(from, to, context.detector().version(), symbol);
        new HistoryReporter(context.clock()).report(symbol, from, to, rows);
    }

    private static void export(AppContext context, Args args) {
        LocalDate from = LocalDate.parse(args.require("from"));
        LocalDate to = args.date("to", LocalDate.now(context.clock().zone()));
        Path out = Path.of(args.value("out").orElse("opportunities.csv"));
        List<OpportunityRow> rows = context.gainOpportunityRepository()
                .findRange(from, to, context.detector().version(), args.value("symbol").orElse(null));
        new OpportunityCsvExporter(context.clock()).export(rows, out);
    }

    private static void hotWindows(AppContext context, Args args) {
        int lookbackDays = args.integer("lookback", context.config().hotWindowLookbackDays());
        LocalDate to = LocalDate.now(context.clock().zone());
        LocalDate from = to.minusDays(lookbackDays);

        List<OpportunityRow> rows = context.gainOpportunityRepository()
                .findForHotWindows(from, to, context.detector().version());
        List<HotWindow> windows = context.hotWindowCalculator().compute(rows, lookbackDays);
        context.hotWindowRepository().replaceAll(context.config().hotWindowBucketMinutes(), lookbackDays, windows);

        System.out.printf("%nComputed %d windows from %d opportunities over %d days%n%n",
                windows.size(), rows.size(), lookbackDays);
        System.out.printf("%-12s %-8s %8s %8s %10s %10s%n",
                "SYMBOL", "FROM", "HITS", "SESSIONS", "HIT-RATE", "LOWER-BND");
        System.out.println("-".repeat(62));
        windows.stream().limit(15).forEach(w -> System.out.printf(Locale.ROOT,
                "%-12s %-8s %8d %8d %9.1f%% %10.3f%n",
                w.symbol() == null ? "(market)" : w.symbol(),
                context.clock().sessionOpen().plusMinutes(w.bucketStartMinute()),
                w.hits(), w.sessions(), w.hitRate() * 100, w.hitRateLcb()));
        System.out.println();
    }

    private static void evaluate(AppContext context, Args args) {
        LocalDate sessionDate = args.date("date",
                context.tradingCalendar().previousTradingDay(LocalDate.now(context.clock().zone()).plusDays(1)));
        int scored = context.predictionEvaluator().evaluate(sessionDate);
        System.out.printf("Scored %d predictions for %s%n", scored, sessionDate);
    }
}
