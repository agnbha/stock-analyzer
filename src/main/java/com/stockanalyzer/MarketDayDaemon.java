package com.stockanalyzer;

import com.stockanalyzer.alert.AlertEngine;
import com.stockanalyzer.alert.AlertPolicy;
import com.stockanalyzer.alert.ConsoleAlertSink;
import com.stockanalyzer.config.AppConfig;
import com.stockanalyzer.live.MarketMonitor;
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
                    ? new AlertEngine(
                            new AlertPolicy(context.alertRepository(), config.alertCooldownMinutes(),
                                    config.alertMaxPerSymbolPerDay(), config.alertMaxPerDay()),
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
                    context.alertRepository(),
                    context.heartbeatRepository(),
                    context.alertPlanner(),
                    context.instrumentRepository(),
                    context.liveCandleRepository(),
                    context.marketEventRepository(),
                    context.tradingDayRepository(),
                    context.tradingCalendar(),
                    new SessionReconciler(context.ingestionService()),
                    new TerminalLiveView(context.clock(), config.monitorAnsi()),
                    context.clock(),
                    context.executor(),
                    new MarketMonitor.MonitorSettings(
                            config.stockSymbols(), config.exchange(), config.segment(),
                            config.intradayIntervalMinutes(), config.monitorPollIntervalSeconds(),
                            config.monitorPostCloseGraceSeconds(), config.modelHorizonMinutes(),
                            config.alertModelMinProbability(), config.monitorPersistLiveCandles()));

            warnIfSessionExceedsDailyBudget(context, config);
            Runtime.getRuntime().addShutdownHook(new Thread(monitor::stop));
            monitor.run(sessionDate);
        }
    }

    /**
     * A full session costs (ticks x symbols) requests. If that does not fit in
     * the daily budget the limiter will simply stop fetching part-way through
     * the afternoon - quietly, because sleeping is what a limiter is supposed to
     * do. Better to say so before the open than to find a flat line at 13:00.
     */
    private static void warnIfSessionExceedsDailyBudget(AppContext context, AppConfig config) {
        int dailyLimit = config.rateLimitPerDay();
        if (dailyLimit <= 0) {
            return;
        }
        long sessionSeconds = context.clock().sessionLengthMinutes() * 60L;
        long ticks = sessionSeconds / Math.max(config.monitorPollIntervalSeconds(), 1);
        long projected = ticks * config.stockSymbols().size();
        if (projected <= dailyLimit) {
            log.info("Projected {} requests this session ({} ticks x {} symbols), within the daily budget of {}",
                    projected, ticks, config.stockSymbols().size(), dailyLimit);
            return;
        }
        log.warn("This session needs about {} requests ({} ticks x {} symbols) but "
                        + "ingest.rate.limit.per.day is {}. The monitor will stall part-way through the "
                        + "afternoon. Raise the limit, lengthen monitor.poll.interval.seconds, or watch "
                        + "fewer symbols.",
                projected, ticks, config.stockSymbols().size(), dailyLimit);
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
