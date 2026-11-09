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
import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.model.LiveSnapshot;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monitor's two speeds: a symbol with a fill today is scanned every ten seconds, every other symbol
 * every three minutes. Driven tick by tick with a controllable clock and a client that counts what it is
 * asked for, so what is fetched, when, and what the view still shows can be read off directly.
 */
class MarketMonitorPollingTest {

    private static final MarketClock CLOCK = MarketClock.nse();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);
    private static final int SLOW = 180;
    private static final int ACTIVE = 10;

    @TempDir
    Path directory;

    private Database database;
    private ExecutorService executor;
    private AlertRepository alerts;
    private TradeRepository trades;
    private final List<Alert> delivered = new CopyOnWriteArrayList<>();
    private final List<LiveSnapshot> snapshots = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong();
    /** How many times each symbol has been fetched. */
    private final Map<String, AtomicInteger> fetched = new ConcurrentHashMap<>();
    /** What a symbol trades at; the default is 100. */
    private final Map<String, Double> prices = new ConcurrentHashMap<>();
    /** The high of one symbol's candle starting at an epoch, keyed "SYMBOL@epoch". */
    private final Map<String, Double> highs = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        executor = Executors.newFixedThreadPool(2);
        alerts = new SqliteAlertRepository(database);
        trades = new SqliteTradeRepository(database);
        now.set(at(9, 32, 0));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        database.close();
    }

    @Test
    @DisplayName("the first tick scans every symbol, and with nothing traded the next is a full interval away")
    void firstTickScansEveryone() {
        MarketMonitor monitor = monitor(List.of("AAA", "BBB", "CCC"));
        monitor.prepare(DAY);

        int pause = monitor.tick(DAY);

        assertEquals(Map.of("AAA", 1, "BBB", 1, "CCC", 1), fetchCounts());
        assertEquals(SLOW, pause);
    }

    @Test
    @DisplayName("a symbol with a fill is scanned on every tick, the others only when three minutes have passed")
    void tradedSymbolsAreScannedEveryTickAndTheRestWhenDue() {
        MarketMonitor monitor = monitor(List.of("AAA", "BBB", "CCC"));
        monitor.prepare(DAY);
        monitor.tick(DAY);                                   // 09:32:00, everyone
        fill("BBB", Side.BUY, at(9, 32, 5));

        now.set(at(9, 32, 10));
        int pause = monitor.tick(DAY);
        assertEquals(Map.of("AAA", 1, "BBB", 2, "CCC", 1), fetchCounts(), "only the traded symbol");
        assertEquals(ACTIVE, pause, "a trade is being watched, so the next look is ten seconds away");

        now.set(at(9, 32, 20));
        monitor.tick(DAY);
        assertEquals(Map.of("AAA", 1, "BBB", 3, "CCC", 1), fetchCounts());

        now.set(at(9, 34, 59));
        monitor.tick(DAY);
        assertEquals(Map.of("AAA", 1, "BBB", 4, "CCC", 1), fetchCounts(), "still inside three minutes of the full poll");

        now.set(at(9, 35, 0));
        pause = monitor.tick(DAY);                           // 180 s after the first tick
        assertEquals(Map.of("AAA", 2, "BBB", 5, "CCC", 2), fetchCounts(), "everyone again, the traded symbol once");
        assertEquals(ACTIVE, pause);
    }

    @Test
    @DisplayName("a sell counts as much as a buy")
    void aSellAloneMakesASymbolActive() {
        MarketMonitor monitor = monitor(List.of("AAA", "BBB"));
        monitor.prepare(DAY);
        monitor.tick(DAY);
        fill("AAA", Side.SELL, at(9, 32, 5));

        now.set(at(9, 32, 10));
        monitor.tick(DAY);

        assertEquals(Map.of("AAA", 2, "BBB", 1), fetchCounts());
    }

    @Test
    @DisplayName("a traded symbol that is not tracked is scanned every tick too, whichever side it traded")
    void anUntrackedTradedSymbolIsScannedEveryTick() {
        fill("INFY", Side.SELL, at(9, 20, 0));   // a position carried in and sold today
        MarketMonitor monitor = monitor(List.of("AAA"));
        monitor.prepare(DAY);

        monitor.tick(DAY);
        assertEquals(Map.of("AAA", 1, "INFY", 1), fetchCounts());

        now.set(at(9, 32, 10));
        monitor.tick(DAY);
        assertEquals(Map.of("AAA", 1, "INFY", 2), fetchCounts());
    }

    @Test
    @DisplayName("with nothing traded, ticks between full polls fetch nothing and wait out the rest of the interval")
    void quietTicksFetchNothing() {
        MarketMonitor monitor = monitor(List.of("AAA", "BBB"));
        monitor.prepare(DAY);
        monitor.tick(DAY);

        now.set(at(9, 33, 10));
        int pause = monitor.tick(DAY);

        assertEquals(Map.of("AAA", 1, "BBB", 1), fetchCounts());
        assertEquals(SLOW - 70, pause, "110 seconds are left of the three minutes");
    }

    @Test
    @DisplayName("the view still shows every symbol, the ones not fetched this tick as they last were")
    void theSnapshotKeepsEverySymbol() {
        MarketMonitor monitor = monitor(List.of("AAA", "BBB", "CCC"));
        monitor.prepare(DAY);
        monitor.tick(DAY);
        fill("BBB", Side.BUY, at(9, 32, 5));
        prices.put("AAA", 105.0);
        prices.put("BBB", 105.0);

        now.set(at(9, 32, 10));
        monitor.tick(DAY);

        LiveSnapshot shown = snapshots.getLast();
        assertEquals(3, shown.symbols().size(), "no symbol drops out of the view");
        Map<String, Double> lastPrices = new TreeMap<>();
        shown.symbols().forEach(state -> lastPrices.put(state.symbol(), state.lastPrice()));
        assertEquals(100.0, lastPrices.get("AAA"), "not fetched since the full poll, so as it was then");
        assertEquals(105.0, lastPrices.get("BBB"), "fetched this tick");
        assertEquals(now.get() + ACTIVE, shown.nextPollEpoch());
    }

    @Test
    @DisplayName("a sell target is announced on the ten-second tick, while the other symbols are not being fetched")
    void aSellTargetIsAnnouncedWithoutWaitingForTheFullPoll() {
        MarketMonitor monitor = monitor(List.of("AAA", "BBB", "CCC"));
        monitor.prepare(DAY);
        monitor.tick(DAY);                                   // 09:32:00
        fill("BBB", Side.BUY, at(9, 32, 5));                 // bought at 100.00
        highs.put("BBB@" + at(9, 33, 0), 100.60);            // +0.6% in the 09:33 candle

        now.set(at(9, 34, 0));
        monitor.tick(DAY);

        assertEquals(List.of("sell.target.0.6"), delivered.stream().map(Alert::rule).toList());
        assertEquals(Map.of("AAA", 1, "BBB", 2, "CCC", 1), fetchCounts(),
                "the announcement came from a tick that fetched only the traded symbol");
    }

    @Test
    @DisplayName("a buy imported while the loop idles ends the wait, so the next tick scans it at once")
    void aFillImportedDuringTheWaitEndsIt() {
        MarketMonitor monitor = monitor(List.of("AAA", "BBB", "CCC"));
        monitor.prepare(DAY);
        assertEquals(SLOW, monitor.tick(DAY));               // 09:32:00, nothing traded: three minutes of quiet
        List<Integer> slept = new ArrayList<>();
        monitor.sleeper = seconds -> {
            slept.add(seconds);
            now.addAndGet(seconds);
            if (now.get() == at(9, 33, 0)) {
                fill("BBB", Side.BUY, at(9, 32, 55));        // the import lands a minute into the wait
            }
        };

        monitor.waitForNextTick(DAY, SLOW);

        assertEquals(60, slept.stream().mapToInt(Integer::intValue).sum(),
                "the wait ended when the fill appeared, not after the three minutes");
        assertTrue(slept.stream().allMatch(seconds -> seconds <= MarketMonitor.FILL_CHECK_SECONDS), "" + slept);
        int pause = monitor.tick(DAY);
        assertEquals(Map.of("AAA", 1, "BBB", 2, "CCC", 1), fetchCounts(), "the bought symbol, and only it");
        assertEquals(ACTIVE, pause);
    }

    @Test
    @DisplayName("with nothing new the wait lasts the whole pause, whatever its length")
    void aQuietWaitLastsThePause() {
        MarketMonitor monitor = monitor(List.of("AAA"));
        monitor.prepare(DAY);
        monitor.tick(DAY);
        List<Integer> slept = new ArrayList<>();
        monitor.sleeper = slept::add;

        monitor.waitForNextTick(DAY, 110);
        assertEquals(110, slept.stream().mapToInt(Integer::intValue).sum());
        assertTrue(slept.stream().allMatch(seconds -> seconds <= MarketMonitor.FILL_CHECK_SECONDS), "" + slept);

        slept.clear();
        monitor.waitForNextTick(DAY, 7);
        assertEquals(List.of(2, 2, 2, 1), slept, "an odd pause ends on the remainder");
    }

    @Test
    @DisplayName("a fill the last tick already saw does not cut the wait short")
    void aKnownFillDoesNotEndTheWait() {
        MarketMonitor monitor = monitor(List.of("AAA", "BBB"));
        monitor.prepare(DAY);
        fill("BBB", Side.BUY, at(9, 31, 0));
        monitor.tick(DAY);
        List<Integer> slept = new ArrayList<>();
        monitor.sleeper = slept::add;

        monitor.waitForNextTick(DAY, ACTIVE);

        assertEquals(ACTIVE, slept.stream().mapToInt(Integer::intValue).sum(), "or the loop would spin");
    }

    @Test
    @DisplayName("a second trade, even in a symbol already watched, ends the wait too")
    void aSecondFillEndsTheWait() {
        MarketMonitor monitor = monitor(List.of("AAA", "BBB"));
        monitor.prepare(DAY);
        fill("BBB", Side.BUY, at(9, 31, 0));
        monitor.tick(DAY);
        List<Integer> slept = new ArrayList<>();
        monitor.sleeper = seconds -> {
            slept.add(seconds);
            if (slept.size() == 2) {
                fill("BBB", Side.SELL, at(9, 32, 3));
            }
        };

        monitor.waitForNextTick(DAY, ACTIVE);

        assertEquals(List.of(2, 2), slept, "a sell may be what the sell targets are waiting for");
    }

    @Test
    @DisplayName("a stopped monitor does not wait at all")
    void aStoppedMonitorDoesNotWait() {
        MarketMonitor monitor = monitor(List.of("AAA"));
        monitor.prepare(DAY);
        monitor.tick(DAY);
        List<Integer> slept = new ArrayList<>();
        monitor.sleeper = slept::add;
        monitor.stop();

        monitor.waitForNextTick(DAY, SLOW);

        assertEquals(List.of(), slept);
    }

    @Test
    @Timeout(20)
    @DisplayName("run as the loop runs it, a buy lands on the fast scan a minute after the full poll, not three")
    void theLoopMovesABoughtSymbolToTheFastScanAtOnce() {
        MarketMonitor monitor = monitor(List.of("AAA", "BBB"));
        monitor.prepare(DAY);
        monitor.sleeper = seconds -> {
            now.addAndGet(seconds);
            if (now.get() == at(9, 33, 0)) {
                fill("BBB", Side.BUY, at(9, 32, 55));        // imported a minute into the quiet interval
            }
            if (snapshots.size() >= 3) {
                monitor.stop();
            }
        };

        monitor.loop(DAY);

        assertEquals(List.of(at(9, 32, 0), at(9, 33, 0), at(9, 33, 10)),
                snapshots.stream().limit(3).map(LiveSnapshot::asOfEpoch).toList(),
                "the full poll, the moment the fill arrived (the old loop slept to 09:35:00), then ten seconds on");
        assertEquals(Map.of("AAA", 1, "BBB", 3), fetchCounts());
    }

    // ---- harness -------------------------------------------------------------------------------------------------

    private Map<String, Integer> fetchCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        fetched.forEach((symbol, count) -> counts.put(symbol, count.get()));
        return counts;
    }

    private void fill(String symbol, Side side, long executedAt) {
        trades.importAll(List.of(new Trade(0, symbol, "t-" + symbol + "-" + executedAt, "o-" + executedAt, DAY,
                executedAt, side, 100, 100.00, Product.MIS, 0, null, Trade.ChargesSource.MODELLED,
                Trade.TradeSource.BROKER, null)), "NSE", "CASH");
    }

    private MarketMonitor monitor(List<String> tracked) {
        CandleDataClient candles = (symbol, exchange, segment, start, end, interval) -> {
            fetched.computeIfAbsent(symbol, key -> new AtomicInteger()).incrementAndGet();
            double close = prices.getOrDefault(symbol, 100.0);
            List<Candle> list = new ArrayList<>();
            for (long ts = at(9, 15, 0); ts + 60 <= now.get(); ts += 60) {
                double high = Math.max(close, highs.getOrDefault(symbol + "@" + ts, close));
                list.add(new Candle(ts, close, high, close - 0.1, close, 1000));
            }
            return new StockCandleSeries(symbol, exchange, segment, list);
        };
        EventDetector noEvents = new EventDetector() {
            @Override
            public List<MarketEvent> detect(com.stockanalyzer.model.TradingSession session, PriorSessionContext c) {
                return List.of();
            }

            @Override
            public String version() {
                return "test";
            }
        };
        IntradaySignalModel noModel = new IntradaySignalModel() {
            @Override
            public List<SignalPrediction> score(com.stockanalyzer.signal.SignalRequest request) {
                return List.of();
            }

            @Override
            public String modelVersion() {
                return "test";
            }
        };
        AlertEngine engine = new AlertEngine(new AlertPolicy(alerts), delivered::add, alerts);
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
        return new MarketMonitor(candles, noWindows, noEvents, new FeatureExtractor(CLOCK), noModel, engine,
                new SellTargetAlerts(List.of(0.6, 1.0), 0.05, 1, 10), trades,
                new SqliteHeartbeatRepository(database), new SqliteInstrumentRepository(database),
                new SqliteLiveCandleRepository(database), new SqliteLiveOpportunityRepository(database),
                new SqliteMarketEventRepository(database), new SqliteTradingDayRepository(database), weekdays, null,
                snapshots::add, CLOCK, executor,
                new MarketMonitor.MonitorSettings(tracked, "NSE", "CASH", 1, SLOW, ACTIVE, 120, 30, true),
                now::get);
    }

    private static long at(int hour, int minute, int second) {
        return CLOCK.epochOf(DAY, LocalTime.of(hour, minute)) + second;
    }
}
