package com.stockanalyzer.canslim;

import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.OpenPosition;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.RealizedLot;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.RealizedLotRepository;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteCanSlimRepository;
import com.stockanalyzer.store.jdbc.SqliteDailyBarRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanSlimServiceTest {

    private static final MarketClock MARKET = MarketClock.nse();

    @TempDir
    Path directory;

    private Database database;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        executor = Executors.newFixedThreadPool(3);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        database.close();
    }

    @Test
    @DisplayName("a scan screens, prices, scores and stores - buys for candidates, sells for holdings")
    void endToEnd() {
        Map<String, List<DailyBar>> prices = Map.of(
                "IDX", TestBars.from(100).trend(130, 112).build(),
                "GROW", TestBars.from(100).trend(150, 70).range(138, 146, 38).add(152, 2000).build(),
                "HELD", TestBars.from(100).trend(120, 100).trend(108, 12).build(),
                "LAG", TestBars.from(100).trend(95, 112).build());
        ScreenedStock grow = new ScreenedStock("GROW", "Grower", Map.of(
                ScreenerRatios.QTR_PROFIT_GROWTH, 40.0, ScreenerRatios.PROFIT_GROWTH_3Y, 30.0));

        CanSlimService service = service(query -> List.of(grow, new ScreenedStock("GONE", "Delisted", Map.of())),
                prices, List.of(new OpenPosition("HELD", Product.CNC, 10, 121.0, 0)), List.of("LAG"));

        CanSlimReport report = service.scan("Return on equity > 17");

        assertEquals(MarketState.CONFIRMED_UPTREND, report.market().state());
        assertEquals(2, report.screened());
        Map<String, CanSlimScore> bySymbol = report.scores().stream()
                .collect(java.util.stream.Collectors.toMap(CanSlimScore::symbol, Function.identity()));
        assertEquals(CanSlimSignal.BUY_BREAKOUT, bySymbol.get("GROW").signal());
        assertEquals(CanSlimSignal.SELL_STOP_LOSS, bySymbol.get("HELD").signal());
        assertEquals(CanSlimScore.Source.HOLDING, bySymbol.get("HELD").source());
        assertEquals(CanSlimSignal.NONE, bySymbol.get("GONE").signal(), "no prices, no signal");
        assertEquals(1, report.failures().size());
        assertTrue(report.failures().getFirst().startsWith("GONE"));
        assertEquals(CanSlimSignal.SELL_STOP_LOSS, report.scores().getFirst().signal(), "sells are listed first");

        assertEquals(4, count("SELECT COUNT(*) FROM canslim_score"));
        assertEquals("BUY_BREAKOUT", text("SELECT signal FROM v_canslim_latest WHERE symbol = 'GROW'"));
        assertEquals(150.0, Double.parseDouble(text("SELECT pivot FROM v_canslim_latest WHERE symbol = 'GROW'")));
        assertEquals(prices.get("GROW").size(),
                count("SELECT COUNT(*) FROM v_daily_bars WHERE symbol = 'GROW'"));
        assertEquals(1, count("SELECT COUNT(*) FROM v_daily_bars WHERE symbol = 'GROW' AND sma50 IS NOT NULL "
                + "AND session_date = '" + prices.get("GROW").getLast().date() + "'"));
    }

    @Test
    @DisplayName("without a query only holdings and the universe are scored")
    void noScreener() {
        CanSlimService service = service(query -> {
            throw new AssertionError("the screener must not be called");
        }, Map.of("IDX", TestBars.from(100).trend(130, 112).build(),
                "LAG", TestBars.from(100).trend(95, 112).build()), List.of(), List.of("LAG"));

        CanSlimReport report = service.scan(null);

        assertEquals(0, report.screened());
        assertEquals(List.of("LAG"), report.scores().stream().map(CanSlimScore::symbol).toList());
    }

    private CanSlimService service(ScreenerClient screener, Map<String, List<DailyBar>> prices,
                                   List<OpenPosition> held, List<String> universe) {
        CandleDataClient candles = (symbol, exchange, segment, start, end, interval) -> {
            List<DailyBar> bars = prices.get(symbol);
            if (bars == null) {
                throw new IllegalStateException("unknown symbol " + symbol);
            }
            return new StockCandleSeries(symbol, exchange, segment, bars.stream()
                    .map(b -> new Candle(MARKET.sessionOpenEpoch(b.date()), b.open(), b.high(), b.low(),
                            b.close(), b.volume()))
                    .toList());
        };
        RealizedLotRepository positions = new RealizedLotRepository() {
            @Override
            public void replaceAll(List<RealizedLot> lots, List<OpenPosition> open, String exchange, String segment) {
            }

            @Override
            public List<RealizedLot> findClosedBetween(LocalDate from, LocalDate to) {
                return List.of();
            }

            @Override
            public List<OpenPosition> openPositions() {
                return held;
            }
        };
        return new CanSlimService(screener, candles, new SqliteInstrumentRepository(database),
                new SqliteDailyBarRepository(database), new SqliteCanSlimRepository(database), positions,
                MARKET, Clock.fixed(Instant.parse("2025-08-01T13:00:00Z"), ZoneOffset.UTC), executor,
                CanSlimCriteria.defaults(),
                new CanSlimService.Settings("NSE", "CASH", "IDX", 400, 150, universe));
    }

    private int count(String sql) {
        return Integer.parseInt(text(sql));
    }

    private String text(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                rs.next();
                return rs.getString(1);
            }
        });
    }
}
