package com.stockanalyzer.alert;

import com.stockanalyzer.model.HotWindow;
import com.stockanalyzer.model.ScheduledAlert;
import com.stockanalyzer.store.HotWindowRepository;
import com.stockanalyzer.util.MarketClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
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

    private static final Logger log = LoggerFactory.getLogger(SessionAlertPlanner.class);

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
    private final int skipOpeningMinutes;
    private final int maxHotWindowAlerts;

    /**
     * @param skipOpeningMinutes windows starting within this many minutes of the open are not alerted on:
     *                           the day's low, and so the start of a top-3 window, often sits at the open,
     *                           which makes the opening bucket "hot" for nearly every symbol
     * @param maxHotWindowAlerts the most hot-window alerts planned for one day; the strongest are kept
     */
    public SessionAlertPlanner(HotWindowRepository hotWindowRepository, MarketClock clock, int bucketMinutes,
                               int lookbackDays, int leadTimeMinutes, int minSessions, double minLowerBound,
                               int skipOpeningMinutes, int maxHotWindowAlerts) {
        this.hotWindowRepository = hotWindowRepository;
        this.clock = clock;
        this.bucketMinutes = bucketMinutes;
        this.lookbackDays = lookbackDays;
        this.leadTimeMinutes = leadTimeMinutes;
        this.minSessions = minSessions;
        this.minLowerBound = minLowerBound;
        this.skipOpeningMinutes = skipOpeningMinutes;
        this.maxHotWindowAlerts = maxHotWindowAlerts;
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

        planned.addAll(hotWindowAlerts(sessionDate, symbols));

        planned.sort((a, b) -> Long.compare(a.fireAtEpoch(), b.fireAtEpoch()));
        return planned;
    }

    /**
     * The strongest qualifying windows, at most {@code maxHotWindowAlerts}. Chosen
     * here, at plan time and by lower bound, rather than left to a runtime cap -
     * which would keep whichever windows happen to come first in the day instead
     * of the best ones, and would not show in the plan.
     */
    private List<ScheduledAlert> hotWindowAlerts(LocalDate sessionDate, List<String> symbols) {
        record Candidate(String symbol, HotWindow window) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (String symbol : symbols) {
            for (HotWindow window : hotWindowRepository.find(symbol, bucketMinutes, lookbackDays)) {
                if (window.symbol() == null || window.sessions() < minSessions
                        || window.hitRateLcb() < minLowerBound
                        || window.bucketStartMinute() < skipOpeningMinutes) {
                    continue;
                }
                if (clock.sessionOpen().plusMinutes(window.bucketStartMinute()).minusMinutes(leadTimeMinutes)
                        .isBefore(clock.sessionOpen().minusMinutes(15))) {
                    continue;
                }
                candidates.add(new Candidate(symbol, window));
            }
        }
        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.window().hitRateLcb()).reversed()
                .thenComparing(Candidate::symbol)
                .thenComparingInt(c -> c.window().bucketStartMinute()));
        if (candidates.size() > maxHotWindowAlerts) {
            log.info("{} hot windows qualify; planning alerts for the strongest {}", candidates.size(),
                    maxHotWindowAlerts);
        }

        List<ScheduledAlert> alerts = new ArrayList<>();
        for (Candidate candidate : candidates.subList(0, Math.min(candidates.size(), maxHotWindowAlerts))) {
            HotWindow window = candidate.window();
            LocalTime windowStart = clock.sessionOpen().plusMinutes(window.bucketStartMinute());
            String message = String.format(Locale.ROOT,
                    "%s approaching a historically strong window (%s-%s): top-%d entry on %d of %d sessions, "
                            + "median %+.2f%%",
                    candidate.symbol(), windowStart, windowStart.plusMinutes(window.bucketMinutes()),
                    3, window.hits(), window.sessions(), window.medianGainPct());
            alerts.add(new ScheduledAlert(0, sessionDate,
                    clock.epochOf(sessionDate, windowStart.minusMinutes(leadTimeMinutes)), candidate.symbol(),
                    RULE_HOT_WINDOW, message, ScheduledAlert.Status.PENDING));
        }
        return alerts;
    }

    private LocalTime midpoint() {
        return clock.sessionOpen().plusMinutes(clock.sessionLengthMinutes() / 2);
    }

    private ScheduledAlert lifecycle(LocalDate sessionDate, LocalTime time, String rule, String message) {
        return new ScheduledAlert(0, sessionDate, clock.epochOf(sessionDate, time), null, rule, message,
                ScheduledAlert.Status.PENDING);
    }
}
