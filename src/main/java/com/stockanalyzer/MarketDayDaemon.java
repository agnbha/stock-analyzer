package com.stockanalyzer;

import com.stockanalyzer.alert.AlertEngine;
import com.stockanalyzer.alert.AlertPolicy;
import com.stockanalyzer.alert.ConsoleAlertSink;
import com.stockanalyzer.config.AppConfig;
import com.stockanalyzer.live.LiveFillImporter;
import com.stockanalyzer.live.MarketMonitor;
import com.stockanalyzer.live.PriorSessionBackfill;
import com.stockanalyzer.live.SessionReconciler;
import com.stockanalyzer.live.TerminalLiveView;
import com.stockanalyzer.store.HeartbeatRepository;
import com.stockanalyzer.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Parts 3 and 4: the market-hours process. Polls for new candles, keeps the
 * top-3-so-far up to date, fires alerts, and reconciles against the
 * authoritative tape at the close.
 *
 * <pre>
 *   (no args)      run for today
 *   --date D       run for a specific session
 *   --dry-run      console alerts only, no desktop notifications
 *   status         print the last heartbeat and exit
 * </pre>
 */
public final class MarketDayDaemon {

    private static final Logger log = LoggerFactory.getLogger(MarketDayDaemon.class);
    private static final Path LOCK_FILE = Path.of("data", "market-day-daemon.lock");

    public static void main(String[] args) {
        Args parsed = Args.parse(args);
        if ("status".equals(parsed.command(""))) {
            status();
            return;
        }

        // One daemon at a time, or every alert fires twice.
        try (var channel = java.nio.channels.FileChannel.open(ensureLockFile(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = tryLock(channel);
            if (lock == null) {
                System.err.println("Another market day daemon is already running (" + LOCK_FILE + ")");
                System.exit(1);
            }
            run(parsed);
        } catch (IOException e) {
            throw new IllegalStateException("Could not acquire the daemon lock", e);
        }
    }

    private static void run(Args args) {
        AppConfig config = AppConfig.load();
        try (AppContext context = new AppContext(config)) {
            LocalDate sessionDate = args.date("date", LocalDate.now(context.clock().zone()));

            AlertEngine alertEngine = args.flag("dry-run")
                    ? new AlertEngine(new AlertPolicy(context.alertRepository()),
                            new ConsoleAlertSink(context.clock()),
                            context.alertRepository())
                    : context.alertEngine();

            MarketMonitor monitor = new MarketMonitor(
                    context.candleDataClient(),
                    context.detector(),
                    context.eventDetector(),
                    context.featureExtractor(),
                    context.signalModel(),
                    alertEngine,
                    context.sellTargetAlerts(),
                    context.tradeRepository(),
                    context.heartbeatRepository(),
                    context.instrumentRepository(),
                    context.liveCandleRepository(),
                    context.liveOpportunityRepository(),
                    context.marketEventRepository(),
                    context.tradingDayRepository(),
                    context.tradingCalendar(),
                    new SessionReconciler(context.ingestionService(),
                            context.liveCandleRepository(), context.liveOpportunityRepository()),
                    new TerminalLiveView(context.clock(), config.monitorAnsi()),
                    context.clock(),
                    context.executor(),
                    new MarketMonitor.MonitorSettings(
                            config.stockSymbols(), config.exchange(), config.segment(),
                            config.intradayIntervalMinutes(), config.monitorPollIntervalSeconds(),
                            config.monitorActivePollIntervalSeconds(),
                            config.monitorPostCloseGraceSeconds(), config.modelHorizonMinutes(),
                            config.monitorPersistLiveCandles()));

            warnIfSessionExceedsDailyBudget(context, config);
            // Yesterday's close is today's baseline: if the evening batch missed it, fetch it now.
            new PriorSessionBackfill(context.ingestionService(), context.tradingCalendar())
                    .ensure(sessionDate, config.stockSymbols(), config.exchange(), config.segment(),
                            config.intradayIntervalMinutes());
            Runtime.getRuntime().addShutdownHook(new Thread(monitor::stop));
            try (LiveFillImporter fills = new LiveFillImporter(
                    () -> context.brokerFillSync().sync(context.brokerExecutionClient(), sessionDate, sessionDate),
                    config.tradesLiveImportIntervalMinutes())) {
                fills.start();
                monitor.run(sessionDate);
            }
        }
    }

    /** Fewest symbols traded in a day that the request budget should still cover at the active speed. */
    private static final int ACTIVE_SYMBOLS_TO_AFFORD = 5;

    /**
     * Every symbol costs one request per slow poll, and a symbol traded today one per active poll for the
     * rest of the session. If that does not fit in the daily budget the limiter will simply stop fetching
     * part-way through the afternoon - quietly, because sleeping is what a limiter is supposed to do.
     * Better to say so before the open than to find a flat line at 13:00. The number of symbols that will
     * be traded is not known at the open, so this says how many the budget has room for.
     */
    private static void warnIfSessionExceedsDailyBudget(AppContext context, AppConfig config) {
        int dailyLimit = config.rateLimitPerDay();
        if (dailyLimit <= 0) {
            return;
        }
        long sessionSeconds = context.clock().sessionLengthMinutes() * 60L;
        int symbols = config.stockSymbols().size();
        long slowTicks = sessionSeconds / Math.max(config.monitorPollIntervalSeconds(), 1);
        long slowRequests = slowTicks * symbols;
        long activeTicks = sessionSeconds / Math.max(config.monitorActivePollIntervalSeconds(), 1);
        long affordable = slowRequests >= dailyLimit ? 0 : (dailyLimit - slowRequests) / activeTicks;
        if (slowRequests > dailyLimit) {
            log.warn("This session needs about {} requests ({} polls x {} symbols) but "
                            + "ingest.rate.limit.per.day is {}. The monitor will stall part-way through the "
                            + "afternoon. Raise the limit, lengthen monitor.poll.interval.seconds, or watch "
                            + "fewer symbols.",
                    slowRequests, slowTicks, symbols, dailyLimit);
        } else if (affordable < ACTIVE_SYMBOLS_TO_AFFORD) {
            log.warn("Scanning {} symbols every {}s takes {} of the {} requests a day ingest.rate.limit.per.day "
                            + "allows, which leaves room to follow only {} traded symbols every {}s. The monitor "
                            + "will stall in the afternoon if more are traded. Raise the limit.",
                    symbols, config.monitorPollIntervalSeconds(), slowRequests, dailyLimit, affordable,
                    config.monitorActivePollIntervalSeconds());
        } else {
            log.info("Projected {} requests for {} symbols every {}s; the daily budget of {} leaves room to "
                            + "follow about {} traded symbols every {}s",
                    slowRequests, symbols, config.monitorPollIntervalSeconds(), dailyLimit, affordable,
                    config.monitorActivePollIntervalSeconds());
        }
    }

    private static void status() {
        try (AppContext context = new AppContext(AppConfig.load())) {
            HeartbeatRepository.Heartbeat heartbeat = context.heartbeatRepository().latest().orElse(null);
            if (heartbeat == null) {
                System.out.println("No heartbeat recorded; the daemon has not run against this database.");
                return;
            }
            long ageSeconds = System.currentTimeMillis() / 1000 - heartbeat.lastTickEpoch();
            System.out.printf("Session %s - last tick %s (%ds ago) - %d symbols - %s%n",
                    heartbeat.sessionDate(),
                    Instant.ofEpochSecond(heartbeat.lastTickEpoch()).atZone(context.clock().zone()).toLocalTime(),
                    ageSeconds, heartbeat.symbolsTracked(), heartbeat.note());
            if (ageSeconds > 600) {
                System.out.println("That is more than 10 minutes old: the daemon is probably not running.");
            }
        }
    }

    private static Path ensureLockFile() throws IOException {
        Path parent = LOCK_FILE.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        return LOCK_FILE;
    }

    private static FileLock tryLock(java.nio.channels.FileChannel channel) {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException | IOException e) {
            return null;
        }
    }

    private MarketDayDaemon() {
    }
}
