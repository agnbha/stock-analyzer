package com.stockanalyzer.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs a job every few minutes for as long as the daemon is up, so trades made
 * during the session reach the journal and the charts while the market is open
 * and not only in the evening batch.
 *
 * <p>The job runs on its own thread: a slow or failing broker call must not hold
 * up a polling tick. A failure is logged once per streak, since the same expired
 * credential would otherwise repeat every interval until someone fixed it.
 */
public final class LiveFillImporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveFillImporter.class);

    private final Runnable job;
    private final int intervalMinutes;
    private final String account;
    private final ScheduledExecutorService scheduler;

    private int consecutiveFailures;

    /** @param intervalMinutes how often to run; zero or less never starts it */
    public LiveFillImporter(Runnable job, int intervalMinutes) {
        this(job, intervalMinutes, null);
    }

    /**
     * One account of several: each runs on its own thread, so one account's broker trouble never holds up another's
     * fills, and its failures are logged as its own.
     *
     * @param account named in the thread and the log; null for the first account, which reads as before
     */
    public LiveFillImporter(Runnable job, int intervalMinutes, String account) {
        this.job = job;
        this.intervalMinutes = intervalMinutes;
        this.account = account;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, account == null ? "fill-import" : "fill-import-" + account);
            thread.setDaemon(true);
            return thread;
        });
    }

    private String forAccount() {
        return account == null ? "" : " for " + account;
    }

    public void start() {
        if (intervalMinutes <= 0) {
            log.info("Live fill import{} is off (trades.live.import.interval.minutes={})", forAccount(), intervalMinutes);
            return;
        }
        scheduler.scheduleWithFixedDelay(this::runOnce, 1, intervalMinutes, TimeUnit.MINUTES);
        log.info("Importing broker fills{} every {} minutes during the session", forAccount(), intervalMinutes);
    }

    /** One attempt; never throws, because a scheduled task that throws is silently cancelled. */
    void runOnce() {
        try {
            job.run();
            if (consecutiveFailures > 0) {
                log.info("Fill import{} recovered after {} failed attempts", forAccount(), consecutiveFailures);
            }
            consecutiveFailures = 0;
        } catch (RuntimeException e) {
            consecutiveFailures++;
            if (consecutiveFailures == 1) {
                log.warn("Could not import broker fills{} (will keep trying every {} minutes): {}",
                        forAccount(), intervalMinutes, e.getMessage());
            } else {
                log.debug("Fill import{} still failing ({} in a row): {}", forAccount(), consecutiveFailures,
                        e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
