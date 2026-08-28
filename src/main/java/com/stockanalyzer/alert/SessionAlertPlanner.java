package com.stockanalyzer.alert;

import com.stockanalyzer.model.HotWindow;
import com.stockanalyzer.model.ScheduledAlert;
import com.stockanalyzer.store.HotWindowRepository;
import com.stockanalyzer.util.MarketClock;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Materialises the day's scheduled alerts at session start, so what will fire
 * can be inspected before it fires rather than discovered as it happens.
 *
 * <p>Two kinds are planned here: the session lifecycle, and the moments when
 * the clock reaches a bucket that has historically produced a top-N entry. Live
 * condition alerts (events, model probabilities) are evaluated on each monitor
 * tick instead, because they depend on what the market is doing.
 */
public final class SessionAlertPlanner {

    public static final String RULE_PRE_OPEN = "session.pre-open";
    public static final String RULE_OPEN = "session.open";
    public static final String RULE_MID = "session.midpoint";
    public static final String RULE_CLOSING = "session.closing";
    public static final String RULE_CLOSE = "session.close";
    public static final String RULE_HOT_WINDOW = "hotwindow.approaching";

    private final HotWindowRepository hotWindowRepository;
    private final MarketClock clock;
    private final int bucketMinutes;
    private final int lookbackDays;
    private final int leadTimeMinutes;
    private final int minSessions;
    private final double minLowerBound;

    public SessionAlertPlanner(HotWindowRepository hotWindowRepository, MarketClock clock, int bucketMinutes,
                               int lookbackDays, int leadTimeMinutes, int minSessions, double minLowerBound) {
        this.hotWindowRepository = hotWindowRepository;
        this.clock = clock;
        this.bucketMinutes = bucketMinutes;
        this.lookbackDays = lookbackDays;
        this.leadTimeMinutes = leadTimeMinutes;
        this.minSessions = minSessions;
        this.minLowerBound = minLowerBound;
    }

    public List<ScheduledAlert> plan(LocalDate sessionDate, List<String> symbols) {
        List<ScheduledAlert> planned = new ArrayList<>();

        planned.add(lifecycle(sessionDate, clock.sessionOpen().minusMinutes(15), RULE_PRE_OPEN,
                "Pre-open: market opens in 15 minutes"));
        planned.add(lifecycle(sessionDate, clock.sessionOpen(), RULE_OPEN, "Market open"));
        planned.add(lifecycle(sessionDate, midpoint(), RULE_MID, "Half way through the session"));
        planned.add(lifecycle(sessionDate, clock.sessionClose().minusMinutes(15), RULE_CLOSING,
                "Closing window: 15 minutes to the bell"));
        planned.add(lifecycle(sessionDate, clock.sessionClose(), RULE_CLOSE, "Market closed"));

        for (String symbol : symbols) {
            for (HotWindow window : hotWindowRepository.find(symbol, bucketMinutes, lookbackDays)) {
                if (window.symbol() == null || window.sessions() < minSessions
                        || window.hitRateLcb() < minLowerBound) {
                    continue;
                }
                LocalTime windowStart = clock.sessionOpen().plusMinutes(window.bucketStartMinute());
                LocalTime fireAt = windowStart.minusMinutes(leadTimeMinutes);
                if (fireAt.isBefore(clock.sessionOpen().minusMinutes(15))) {
                    continue;
                }
                String message = String.format(Locale.ROOT,
                        "%s approaching a historically strong window (%s-%s): top-%d entry on %d of %d sessions, "
                                + "median %+.2f%%",
                        symbol, windowStart, windowStart.plusMinutes(window.bucketMinutes()),
                        3, window.hits(), window.sessions(), window.medianGainPct());
                planned.add(new ScheduledAlert(0, sessionDate, clock.epochOf(sessionDate, fireAt), symbol,
                        RULE_HOT_WINDOW, message, ScheduledAlert.Status.PENDING));
            }
        }

        planned.sort((a, b) -> Long.compare(a.fireAtEpoch(), b.fireAtEpoch()));
        return planned;
    }

    private LocalTime midpoint() {
        return clock.sessionOpen().plusMinutes(clock.sessionLengthMinutes() / 2);
    }

    private ScheduledAlert lifecycle(LocalDate sessionDate, LocalTime time, String rule, String message) {
        return new ScheduledAlert(0, sessionDate, clock.epochOf(sessionDate, time), null, rule, message,
                ScheduledAlert.Status.PENDING);
    }
}
