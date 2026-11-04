package com.stockanalyzer.live;

import com.stockanalyzer.alert.AlertEngine;
import com.stockanalyzer.alert.AlertPolicy;
import com.stockanalyzer.alert.SellTargetAlerts;
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
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.SignalPrediction;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.signal.IntradaySignalModel;
import com.stockanalyzer.store.AlertRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.TradeRepository;
import com.stockanalyzer.store.jdbc.SqliteAlertRepository;
import com.stockanalyzer.store.jdbc.SqliteHeartbeatRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteLiveCandleRepository;
import com.stockanalyzer.store.jdbc.SqliteLiveOpportunityRepository;
import com.stockanalyzer.store.jdbc.SqliteMarketEventRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one alert: after a purchase, say when the price gets to each sell target and which limit to set.
 * Driven through the monitor, with the journal and the alert log in a real database, so the pieces that
 * make it work - reading today's fills, polling a symbol nobody tracks, never sending a target twice,
 * even across a restart - are checked together.
 */
class MarketMonitorSellTargetTest {

    private static final MarketClock CLOCK = MarketClock.nse();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);
    private static final String SYMBOL = "RELIANCE";

    @TempDir
    Path directory;

    private Database database;
    private ExecutorService executor;
    private AlertRepository alerts;
    private TradeRepository trades;
    private final List<Alert> delivered = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong();
    /** The high of the candle starting at an epoch; every other candle trades flat at 100. */
    private final Map<Long, Double> highs = new HashMap<>();
    private List<MarketEvent> detected = List.of();

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        executor = Executors.newFixedThreadPool(2);
        alerts = new SqliteAlertRepository(database);
        trades = new SqliteTradeRepository(database);
        now.set(at(9, 32));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        database.close();
    }

    @Test
    @DisplayName("after a purchase, each target is announced once, when the price gets there")
    void announcesEachTargetOnce() {
        buy(SYMBOL, 100, 100.00, at(9, 20, 10));
        MarketMonitor monitor = monitor(List.of(SYMBOL));
        monitor.prepare(DAY);

        monitor.tick(DAY);
        assertTrue(delivered.isEmpty(), "the price has not moved");

        // 0.6% over 100.00 is 100.60.
        highs.put(at(9, 33), 100.60);
        now.set(at(9, 34));
        monitor.tick(DAY);
        assertEquals(List.of("sell.target.0.6"), rules());
        assertTrue(delivered.getFirst().message().contains("limit at 100.60"), delivered.getFirst().message());

        now.set(at(9, 36));
        monitor.tick(DAY);
        assertEquals(1, delivered.size(), "the same target on a later poll is not sent again");

        highs.put(at(9, 37), 101.00);
        now.set(at(9, 38));
        monitor.tick(DAY);
        assertEquals(List.of("sell.target.0.6", "sell.target.1"), rules());
        assertTrue(delivered.get(1).message().contains("limit at 101.00"), delivered.get(1).message());
    }

    @Test
    @DisplayName("a restarted daemon does not announce a target that was already announced")
    void restartDoesNotRepeat() {
        buy(SYMBOL, 100, 100.00, at(9, 20, 10));
        highs.put(at(9, 33), 100.70);
        now.set(at(9, 34));
        MarketMonitor first = monitor(List.of(SYMBOL));
        first.prepare(DAY);
        first.tick(DAY);
        assertEquals(1, delivered.size());

        MarketMonitor restarted = monitor(List.of(SYMBOL));
        restarted.prepare(DAY);
        now.set(at(9, 36));
        restarted.tick(DAY);

        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("purchases at different prices are measured from their average")
    void usesTheAveragePrice() {
        // 100 @ 100 and 300 @ 101: the average is 100.75, so +0.6% is 101.3545, and the limit to set is the
        // next tick up, 101.40.
        buy(SYMBOL, 100, 100.00, at(9, 20, 10));
        buy(SYMBOL, 300, 101.00, at(9, 25, 10));
        MarketMonitor monitor = monitor(List.of(SYMBOL));
        monitor.prepare(DAY);

        // 101.35 is past +0.6% of the first buy alone (100.60) but short of the average's limit.
        highs.put(at(9, 31), 101.35);
        monitor.tick(DAY);
        assertTrue(delivered.isEmpty(), delivered.toString());

        highs.put(at(9, 31), 101.40);
        monitor.tick(DAY);
        assertEquals(1, delivered.size());
        assertTrue(delivered.getFirst().message().contains("average buy of 100.75"), delivered.getFirst().message());
        assertTrue(delivered.getFirst().message().contains("limit at 101.40"), delivered.getFirst().message());
    }

    @Test
    @DisplayName("a symbol bought today is watched even when it is not one of the tracked symbols")
    void untrackedPurchasesAreWatched() {
        buy("INFY", 50, 100.00, at(9, 20, 10));
        highs.put(at(9, 31), 100.70);
        MarketMonitor monitor = monitor(List.of(SYMBOL));
        monitor.prepare(DAY);

        monitor.tick(DAY);

        assertEquals(List.of("INFY"), delivered.stream().map(Alert::symbol).toList());
    }

    @Test
    @DisplayName("without a purchase there is nothing to say, however far the price runs")
    void noPurchaseNoAlert() {
        highs.put(at(9, 31), 150.00);
        MarketMonitor monitor = monitor(List.of(SYMBOL));
        monitor.prepare(DAY);

        monitor.tick(DAY);

        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("market events are still recorded, but they no longer raise alerts")
    void eventsAreRecordedNotAlerted() {
        detected = List.of(event(EventType.GAP_AND_GO, 9, 25), event(EventType.VWAP_LOSS, 9, 28));
        MarketMonitor monitor = monitor(List.of(SYMBOL));
        monitor.prepare(DAY);

        monitor.tick(DAY);

        assertTrue(delivered.isEmpty());
        assertEquals(2, count("SELECT COUNT(*) FROM event"));
    }

    @Test
    @DisplayName("a journal that cannot be read costs the sell targets for that tick, not the monitoring")
    void unreadableJournalDoesNotStopTheTick() {
        TradeRepository real = trades;
        trades = new TradeRepository() {
            @Override
            public int importAll(List<Trade> fills, String exchange, String segment) {
                return real.importAll(fills, exchange, segment);
            }

            @Override
            public List<Trade> findRange(LocalDate from, LocalDate to) {
                throw new IllegalStateException("database is locked");
            }

            @Override
            public List<Trade> findAllOrdered() {
                return real.findAllOrdered();
            }

            @Override
            public java.util.Optional<Trade> byId(long id) {
                return real.byId(id);
            }
        };
        MarketMonitor monitor = monitor(List.of(SYMBOL));
        monitor.prepare(DAY);

        monitor.tick(DAY);

        assertTrue(delivered.isEmpty());
    }

    @Test
    @DisplayName("a sink that cannot post does not stop the tick either")
    void failingSinkDoesNotStopTheTick() {
        buy(SYMBOL, 100, 100.00, at(9, 20, 10));
        highs.put(at(9, 31), 100.70);
        MarketMonitor monitor = monitor(List.of(SYMBOL), alert -> {
            throw new IllegalStateException("osascript is gone");
        });
        monitor.prepare(DAY);

        monitor.tick(DAY);
    }

    private List<String> rules() {
        return delivered.stream().map(Alert::rule).toList();
    }

    private void buy(String symbol, int quantity, double price, long executedAt) {
        trades.importAll(List.of(new Trade(0, symbol, "t-" + symbol + "-" + executedAt, "o-" + executedAt, DAY,
                executedAt, Side.BUY, quantity, price, Product.MIS, 0, null, Trade.ChargesSource.MODELLED,
                Trade.TradeSource.BROKER, null)), "NSE", "CASH");
    }

    private MarketMonitor monitor(List<String> tracked) {
        return monitor(tracked, delivered::add);
    }

    private MarketMonitor monitor(List<String> tracked, com.stockanalyzer.alert.AlertSink sink) {
        CandleDataClient candles = (symbol, exchange, segment, start, end, interval) -> {
            List<Candle> list = new ArrayList<>();
            for (long ts = at(9, 15); ts + 60 <= now.get(); ts += 60) {
                list.add(new Candle(ts, 100, highs.getOrDefault(ts, 100.0), 99.9, 100, 1000));
            }
            return new StockCandleSeries(symbol, exchange, segment, list);
        };
        List<MarketEvent> events = detected;
        EventDetector eventDetector = new EventDetector() {
            @Override
            public List<MarketEvent> detect(com.stockanalyzer.model.TradingSession session, PriorSessionContext c) {
                return events;
            }

            @Override
            public String version() {
                return "test";
            }
        };
        IntradaySignalModel model = new IntradaySignalModel() {
            @Override
            public List<SignalPrediction> score(com.stockanalyzer.signal.SignalRequest request) {
                return List.of(new SignalPrediction(SYMBOL, DAY, at(9, 30), SignalPrediction.Signal.ENTRY, 0.99, 30, null));
            }

            @Override
            public String modelVersion() {
                return "test";
            }
        };
        AlertEngine engine = new AlertEngine(new AlertPolicy(alerts), sink, alerts);
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
        return new MarketMonitor(candles, noWindows, eventDetector, new FeatureExtractor(CLOCK), model, engine,
                new SellTargetAlerts(List.of(0.6, 1.0), 0.05, 1, 10), trades,
                new SqliteHeartbeatRepository(database), new SqliteInstrumentRepository(database),
                new SqliteLiveCandleRepository(database), new SqliteLiveOpportunityRepository(database),
                new SqliteMarketEventRepository(database), new SqliteTradingDayRepository(database), weekdays, null,
                snapshot -> { }, CLOCK, executor,
                new MarketMonitor.MonitorSettings(tracked, "NSE", "CASH", 1, 150, 120, 30, true),
                now::get);
    }

    private static long at(int hour, int minute) {
        return CLOCK.epochOf(DAY, LocalTime.of(hour, minute));
    }

    private static long at(int hour, int minute, int second) {
        return at(hour, minute) + second;
    }

    private static MarketEvent event(EventType type, int hour, int minute) {
        return new MarketEvent(SYMBOL, DAY, at(hour, minute), type, 1.0);
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
