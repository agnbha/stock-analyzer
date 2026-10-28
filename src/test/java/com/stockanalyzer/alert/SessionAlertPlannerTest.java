package com.stockanalyzer.alert;

import com.stockanalyzer.model.HotWindow;
import com.stockanalyzer.model.ScheduledAlert;
import com.stockanalyzer.store.HotWindowRepository;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionAlertPlannerTest {

    private static final MarketClock CLOCK = MarketClock.nse();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    private static HotWindow window(String symbol, int bucketStart, double lcb) {
        return new HotWindow(symbol, bucketStart, 5, 60, 30, 39, 0.8, lcb, 1.0, 0.9);
    }

    private SessionAlertPlanner planner(Map<String, List<HotWindow>> windows, int skipOpening, int max) {
        HotWindowRepository repository = new HotWindowRepository() {
            @Override
            public void replaceAll(int bucketMinutes, int lookbackDays, List<HotWindow> w) {
            }

            @Override
            public List<HotWindow> find(String symbol, int bucketMinutes, int lookbackDays) {
                return windows.getOrDefault(symbol, List.of());
            }

            @Override
            public List<HotWindow> findAll(int bucketMinutes, int lookbackDays) {
                return List.of();
            }
        };
        return new SessionAlertPlanner(repository, CLOCK, 5, 60, 2, 30, 0.10, skipOpening, max);
    }

    private static List<ScheduledAlert> hotWindows(List<ScheduledAlert> plan) {
        return plan.stream().filter(a -> a.rule().equals(SessionAlertPlanner.RULE_HOT_WINDOW)).toList();
    }

    @Test
    @DisplayName("the opening bucket is not alerted on, however strong it looks")
    void skipsTheOpeningBucket() {
        Map<String, List<HotWindow>> windows = Map.of("AAA", List.of(
                window("AAA", 0, 0.95),
                window("AAA", 5, 0.40),
                window("AAA", 30, 0.30)));

        List<ScheduledAlert> alerts = hotWindows(planner(windows, 5, 20).plan(DAY, List.of("AAA")));

        assertEquals(List.of(LocalTime.of(9, 18), LocalTime.of(9, 43)),
                alerts.stream().map(a -> CLOCK.timeOf(a.fireAtEpoch())).toList());
        assertTrue(alerts.stream().noneMatch(a -> a.payload().contains("09:15-09:20")));
    }

    @Test
    @DisplayName("only the strongest windows are planned when more qualify than the cap allows")
    void keepsTheStrongest() {
        Map<String, List<HotWindow>> windows = new java.util.LinkedHashMap<>();
        List<String> symbols = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String symbol = String.format("S%02d", i);
            symbols.add(symbol);
            // S29 is strongest, S00 weakest; all in the same late-morning bucket.
            windows.put(symbol, List.of(window(symbol, 60, 0.20 + i * 0.01)));
        }

        List<ScheduledAlert> alerts = hotWindows(planner(windows, 5, 20).plan(DAY, symbols));

        assertEquals(20, alerts.size());
        List<String> kept = alerts.stream().map(ScheduledAlert::symbol).toList();
        assertTrue(kept.contains("S29") && kept.contains("S10"));
        assertTrue(!kept.contains("S09") && !kept.contains("S00"), "the ten weakest are dropped: " + kept);
    }

    @Test
    @DisplayName("the cap counts hot-window alerts only, not the session lifecycle")
    void lifecycleIsNotCapped() {
        List<ScheduledAlert> plan = planner(Map.of(), 5, 0).plan(DAY, List.of("AAA"));

        assertEquals(5, plan.size(), "pre-open, open, midpoint, closing, close");
        assertTrue(hotWindows(plan).isEmpty());
    }

    @Test
    @DisplayName("thin evidence and market-wide windows still do not qualify")
    void evidenceGateStillApplies() {
        Map<String, List<HotWindow>> windows = Map.of("AAA", List.of(
                new HotWindow("AAA", 30, 5, 60, 5, 8, 0.6, 0.30, 1.0, 1.0),
                window("AAA", 40, 0.05),
                new HotWindow(null, 50, 5, 60, 900, 2000, 0.45, 0.43, 1.0, 1.0)));

        assertTrue(hotWindows(planner(windows, 5, 20).plan(DAY, List.of("AAA"))).isEmpty());
    }
}
