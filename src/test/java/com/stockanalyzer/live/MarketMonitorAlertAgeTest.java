package com.stockanalyzer.live;

import com.stockanalyzer.alert.AlertEngine;
import com.stockanalyzer.alert.AlertPolicy;
import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.features.EventDetector;
import com.stockanalyzer.features.FeatureExtractor;
import com.stockanalyzer.intraday.GainOpportunityDetector;
import com.stockanalyzer.intraday.TradingCalendar;
import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.EventType;
import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.model.MarketEvent;
import com.stockanalyzer.model.ScheduledAlert;
import com.stockanalyzer.model.SignalPrediction;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.signal.IntradaySignalModel;
import com.stockanalyzer.store.AlertRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteAlertRepository;
import com.stockanalyzer.store.jdbc.SqliteHeartbeatRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteLiveCandleRepository;
import com.stockanalyzer.store.jdbc.SqliteLiveOpportunityRepository;
import com.stockanalyzer.store.jdbc.SqliteMarketEventRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daemon that starts at 10:08 instead of 08:58 detects every event since the
 * open on its first tick. Those must be recorded, but not announced: announcing
 * them is what used the day's whole alert cap in one burst.
 */
class MarketMonitorAlertAgeTest {

    private static final MarketClock CLOCK = MarketClock.nse();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final String SYMBOL = "RELIANCE";

    @TempDir
    Path directory;

    private Database database;
    private ExecutorService executor;
    private AlertRepository alerts;
    private final List<Alert> delivered = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong();

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        executor = Executors.newFixedThreadPool(2);
        alerts = new SqliteAlertRepository(database);
        now.set(at(10, 8));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        database.close();
    }

    @Test
    @DisplayName("a late start announces the event that just happened, not the morning's")
    void lateStartSkipsOldEvents() {
        List<MarketEvent> detected = List.of(
                event(EventType.GAP_AND_GO, 9, 15),
                event(EventType.VWAP_LOSS, 9, 22),
                event(EventType.VWAP_RECLAIM, 9, 42),
                event(EventType.OPENING_RANGE_BREAKOUT, 10, 5));

        tick(monitor(detected, List.of(), 10));

        assertEquals(List.of("event.opening_range_breakout"), delivered.stream().map(Alert::rule).toList());
    }

    @Test
    @DisplayName("the old events are still recorded, so reasons and dashboards have them")
    void oldEventsAreStillStored() {
        tick(monitor(List.of(event(EventType.GAP_AND_GO, 9, 15), event(EventType.VWAP_LOSS, 9, 22)), List.of(), 10));

        assertTrue(delivered.isEmpty());
        assertEquals(2, count("SELECT COUNT(*) FROM event"));
    }

    @Test
    @DisplayName("a burst of old events no longer spends the day's alert cap")
    void capSurvivesTheStart() {
        List<MarketEvent> morning = new ArrayList<>();
        for (int minute = 20; minute < 55; minute += 2) {
            morning.add(event(EventType.VWAP_LOSS, 9, minute));
        }

        tick(monitor(morning, List.of(), 10));
        assertEquals(0, delivered.size());

        // Later, a real event arrives and there is still room to announce it.
        now.set(at(10, 40));
        tick(monitor(List.of(event(EventType.GAP_AND_GO, 10, 38)), List.of(), 10));
        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("scheduled alerts that fell due before the start are skipped, not fired late")
    void overdueScheduledAlertsAreSkipped() {
        alerts.replaceSchedule(DAY, List.of(
                new ScheduledAlert(0, DAY, at(9, 0), null, "session.pre-open", "Pre-open", ScheduledAlert.Status.PENDING),
                new ScheduledAlert(0, DAY, at(10, 6), SYMBOL, "hotwindow.approaching", "In 2 minutes",
                        ScheduledAlert.Status.PENDING)));

        tick(monitor(List.of(), List.of(), 10));

        assertEquals(List.of("hotwindow.approaching"), delivered.stream().map(Alert::rule).toList());
        assertEquals(1, count("SELECT COUNT(*) FROM alert_schedule WHERE status = 'SKIPPED'"));
        assertEquals(0, alerts.pendingSchedule(DAY).size());
    }

    @Test
    @DisplayName("an old model signal does not outrank the current one, or fire at all")
    void oldSignalsAreIgnored() {
        List<SignalPrediction> predictions = List.of(
                prediction(9, 30, 0.99),
                prediction(10, 5, 0.70));

        tick(monitor(List.of(event(EventType.GAP_AND_GO, 10, 6)), predictions, 10));

        List<Alert> signals = delivered.stream().filter(a -> a.rule().equals("model.entry")).toList();
        assertEquals(1, signals.size());
        assertTrue(signals.getFirst().message().contains("0.70"), signals.getFirst().message());
    }

    @Test
    @DisplayName("a longer window lets older events through")
    void windowIsConfigurable() {
        tick(monitor(List.of(event(EventType.VWAP_LOSS, 9, 42)), List.of(), 40));

        assertEquals(1, delivered.size());
    }

    private static void tick(MarketMonitor monitor) {
        monitor.prepare(DAY);
        monitor.tick(DAY);
    }

    private MarketMonitor monitor(List<MarketEvent> detected, List<SignalPrediction> predictions,
                                  int maxAgeMinutes) {
        CandleDataClient candles = (symbol, exchange, segment, start, end, interval) -> {
            List<Candle> list = new ArrayList<>();
            for (long ts = at(9, 15); ts + 60 <= now.get(); ts += 60) {
                list.add(new Candle(ts, 100, 101, 99, 100, 1000));
            }
            return new StockCandleSeries(symbol, exchange, segment, list);
        };
        EventDetector events = new EventDetector() {
            @Override
            public List<MarketEvent> detect(com.stockanalyzer.model.TradingSession session, PriorSessionContext c) {
                return detected;
            }

            @Override
            public String version() {
                return "test";
            }
        };
        IntradaySignalModel model = new IntradaySignalModel() {
            @Override
            public List<SignalPrediction> score(com.stockanalyzer.signal.SignalRequest request) {
                return predictions;
            }

            @Override
            public String modelVersion() {
                return "test";
            }
        };
        AlertEngine engine = new AlertEngine(new AlertPolicy(alerts, 15, 3, 30), delivered::add, alerts);
        TradingCalendar weekdays = new TradingCalendar() {
            @Override
            public boolean isTradingDay(LocalDate date) {
                return true;
            }

            @Override
            public List<LocalDate> tradingDaysBetween(LocalDate from, LocalDate to) {
                return List.of();
            }

            @Override
            public LocalDate previousTradingDay(LocalDate date) {
                return date.minusDays(1);
            }
        };
        GainOpportunityDetector noWindows = new GainOpportunityDetector() {
            @Override
            public List<GainOpportunity> detect(List<Candle> sessionCandles) {
                return List.of();
            }

            @Override
            public String version() {
                return "test";
            }
        };
        return new MarketMonitor(candles, noWindows, events, new FeatureExtractor(CLOCK), model,
                engine, alerts, new SqliteHeartbeatRepository(database), null,
                new SqliteInstrumentRepository(database), new SqliteLiveCandleRepository(database),
                new SqliteLiveOpportunityRepository(database), new SqliteMarketEventRepository(database),
                new SqliteTradingDayRepository(database), weekdays, null, snapshot -> { }, CLOCK, executor,
                new MarketMonitor.MonitorSettings(List.of(SYMBOL), "NSE", "CASH", 1, 150, 120, 30, 0.65,
                        true, maxAgeMinutes),
                now::get);
    }

    private static long at(int hour, int minute) {
        return CLOCK.epochOf(DAY, LocalTime.of(hour, minute));
    }

    private static MarketEvent event(EventType type, int hour, int minute) {
        return new MarketEvent(SYMBOL, DAY, at(hour, minute), type, 1.0);
    }

    private static SignalPrediction prediction(int hour, int minute, double probability) {
        return new SignalPrediction(SYMBOL, DAY, at(hour, minute), SignalPrediction.Signal.ENTRY, probability, 30,
                null);
    }

    private int count(String sql) {
        return database.read(connection -> {
            try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }
}
