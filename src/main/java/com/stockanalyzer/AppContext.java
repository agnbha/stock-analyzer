package com.stockanalyzer;

import com.stockanalyzer.alert.AlertEngine;
import com.stockanalyzer.alert.AlertPolicy;
import com.stockanalyzer.alert.AlertSink;
import com.stockanalyzer.alert.CompositeAlertSink;
import com.stockanalyzer.alert.ConsoleAlertSink;
import com.stockanalyzer.alert.FileAlertSink;
import com.stockanalyzer.alert.MacNotificationSink;
import com.stockanalyzer.alert.SessionAlertPlanner;
import com.stockanalyzer.auth.ChecksumGrowwAuthenticator;
import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.client.CandleRangeChunker;
import com.stockanalyzer.client.ChunkedCandleDataClient;
import com.stockanalyzer.client.GrowwCandleDataClient;
import com.stockanalyzer.client.RateLimitedCandleDataClient;
import com.stockanalyzer.client.RateLimiter;
import com.stockanalyzer.client.RetryingCandleDataClient;
import com.stockanalyzer.client.TokenBucketRateLimiter;
import com.stockanalyzer.config.AppConfig;
import com.stockanalyzer.features.EventDetector;
import com.stockanalyzer.features.FeatureExtractor;
import com.stockanalyzer.features.RuleEventDetector;
import com.stockanalyzer.intraday.BackfillPlanner;
import com.stockanalyzer.intraday.DailyIngestionService;
import com.stockanalyzer.intraday.DailySummaryBuilder;
import com.stockanalyzer.intraday.DefaultTradingCalendar;
import com.stockanalyzer.intraday.FileHolidaySource;
import com.stockanalyzer.intraday.GainOpportunityDetector;
import com.stockanalyzer.intraday.PriceBasis;
import com.stockanalyzer.intraday.RecomputeService;
import com.stockanalyzer.intraday.SessionSplitter;
import com.stockanalyzer.intraday.TopKNonOverlappingDetector;
import com.stockanalyzer.intraday.TradingCalendar;
import com.stockanalyzer.model.AlertSeverity;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.signal.FallbackSignalModel;
import com.stockanalyzer.signal.HotWindowCalculator;
import com.stockanalyzer.signal.HotWindowSignalModel;
import com.stockanalyzer.signal.IntradaySignalModel;
import com.stockanalyzer.signal.PredictionEvaluator;
import com.stockanalyzer.signal.RestIntradaySignalModel;
import com.stockanalyzer.store.AlertRepository;
import com.stockanalyzer.store.CalendarRepository;
import com.stockanalyzer.store.CandleRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.GainOpportunityRepository;
import com.stockanalyzer.store.HeartbeatRepository;
import com.stockanalyzer.store.HotWindowRepository;
import com.stockanalyzer.store.IngestionRunRepository;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.MarketEventRepository;
import com.stockanalyzer.store.PnlPeriodRepository;
import com.stockanalyzer.store.PredictionRepository;
import com.stockanalyzer.store.RealizedLotRepository;
import com.stockanalyzer.store.SchemaMigrator;
import com.stockanalyzer.store.TradeRepository;
import com.stockanalyzer.store.TradingDayRepository;
import com.stockanalyzer.store.jdbc.SqliteAlertRepository;
import com.stockanalyzer.store.jdbc.SqliteAccountBalanceRepository;
import com.stockanalyzer.store.jdbc.SqliteAttributionRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeReasonRepository;
import com.stockanalyzer.store.jdbc.SqliteCalendarRepository;
import com.stockanalyzer.store.jdbc.SqliteCandleRepository;
import com.stockanalyzer.store.jdbc.SqliteGainOpportunityRepository;
import com.stockanalyzer.store.jdbc.SqliteHeartbeatRepository;
import com.stockanalyzer.store.jdbc.SqliteHotWindowRepository;
import com.stockanalyzer.store.jdbc.SqliteIngestionRunRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteMarketEventRepository;
import com.stockanalyzer.store.jdbc.SqlitePnlPeriodRepository;
import com.stockanalyzer.store.jdbc.SqlitePredictionRepository;
import com.stockanalyzer.store.jdbc.SqliteRealizedLotRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import com.stockanalyzer.store.AccountBalanceRepository;
import com.stockanalyzer.store.AttributionRepository;
import com.stockanalyzer.store.TradeReasonRepository;
import com.stockanalyzer.trade.CaptureAnalyzer;
import com.stockanalyzer.trade.ChargeModel;
import com.stockanalyzer.trade.ChargeRates;
import com.stockanalyzer.trade.FifoLotMatcher;
import com.stockanalyzer.trade.GrowwChargeModel;
import com.stockanalyzer.trade.PeriodAggregator;
import com.stockanalyzer.trade.PeriodBounds;
import com.stockanalyzer.trade.ReasonAttributor;
import com.stockanalyzer.trade.TradeJournalService;
import com.stockanalyzer.util.MarketClock;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The composition root shared by every entry point.
 *
 * <p>All concrete-to-interface wiring happens here and nowhere else, so no
 * service class knows it is talking to Groww specifically or to SQLite
 * specifically. The three mains differ only in which of these collaborators
 * they ask for.
 */
public final class AppContext implements AutoCloseable {

    private final AppConfig config;
    private final MarketClock clock;
    private final Database database;
    private final ExecutorService executor;
    private final HttpClient httpClient;

    private final InstrumentRepository instrumentRepository;
    private final TradingDayRepository tradingDayRepository;
    private final GainOpportunityRepository gainOpportunityRepository;
    private final CandleRepository candleRepository;
    private final CalendarRepository calendarRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final HotWindowRepository hotWindowRepository;
    private final MarketEventRepository marketEventRepository;
    private final PredictionRepository predictionRepository;
    private final AlertRepository alertRepository;
    private final HeartbeatRepository heartbeatRepository;
    private final TradeRepository tradeRepository;
    private final RealizedLotRepository realizedLotRepository;
    private final PnlPeriodRepository pnlPeriodRepository;
    private final AttributionRepository attributionRepository;
    private final TradeReasonRepository tradeReasonRepository;
    private final AccountBalanceRepository accountBalanceRepository;

    // Built on first use: reading the journal or a stored report must not require
    // API credentials, and constructing the authenticator does.
    private final Supplier<GrowwAuthenticator> authenticator;
    private final Supplier<CandleDataClient> candleDataClient;
    private final GainOpportunityDetector detector;
    private final DailySummaryBuilder summaryBuilder;
    private final TradingCalendar tradingCalendar;
    private final DailyIngestionService ingestionService;
    private final RecomputeService recomputeService;
    private final EventDetector eventDetector;
    private final FeatureExtractor featureExtractor;
    private final IntradaySignalModel signalModel;
    private final TradeJournalService tradeJournalService;

    public AppContext(AppConfig config) {
        this.config = config;
        this.clock = new MarketClock(ZoneId.of(config.monitorTimezone()),
                LocalTime.parse(config.monitorSessionOpen()), LocalTime.parse(config.monitorSessionClose()));
        this.database = Database.open(config.databaseUrl(), config.databaseBusyTimeoutMillis());
        new SchemaMigrator(database).migrate();
        this.executor = Executors.newFixedThreadPool(config.fetchConcurrency());
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        this.instrumentRepository = new SqliteInstrumentRepository(database);
        this.tradingDayRepository = new SqliteTradingDayRepository(database);
        this.gainOpportunityRepository = new SqliteGainOpportunityRepository(database);
        this.candleRepository = new SqliteCandleRepository(database);
        this.calendarRepository = new SqliteCalendarRepository(database);
        this.ingestionRunRepository = new SqliteIngestionRunRepository(database);
        this.hotWindowRepository = new SqliteHotWindowRepository(database);
        this.marketEventRepository = new SqliteMarketEventRepository(database);
        this.predictionRepository = new SqlitePredictionRepository(database);
        this.alertRepository = new SqliteAlertRepository(database);
        this.heartbeatRepository = new SqliteHeartbeatRepository(database);
        this.tradeRepository = new SqliteTradeRepository(database);
        this.realizedLotRepository = new SqliteRealizedLotRepository(database);
        this.pnlPeriodRepository = new SqlitePnlPeriodRepository(database);
        this.attributionRepository = new SqliteAttributionRepository(database);
        this.tradeReasonRepository = new SqliteTradeReasonRepository(database);
        this.accountBalanceRepository = new SqliteAccountBalanceRepository(database);

        this.authenticator = memoize(() -> new ChecksumGrowwAuthenticator(httpClient,
                config.growwBaseUrl(), config.growwApiKey(), config.growwApiSecret()));

        // Chunk outermost so a retry only repeats the one chunk that failed; the rate
        // limiter sits innermost so every real request passes through it exactly once.
        RateLimiter rateLimiter = new TokenBucketRateLimiter(
                config.rateLimitPerSecond(), config.rateLimitPerMinute());
        this.candleDataClient = memoize(() -> {
            CandleDataClient raw = new GrowwCandleDataClient(httpClient, authenticator.get(),
                    config.growwBaseUrl());
            return new ChunkedCandleDataClient(
                    new RetryingCandleDataClient(
                            new RateLimitedCandleDataClient(raw, rateLimiter),
                            config.ingestMaxRetries(), config.ingestRetryBackoffMillis()),
                    new CandleRangeChunker(config.backfillMaxDaysPerRequest()));
        });

        this.detector = new TopKNonOverlappingDetector(config.intradayTopN(),
                PriceBasis.valueOf(config.intradayPriceBasis()), config.intradayMinHoldCandles(),
                config.intradayMinGainPct());
        this.summaryBuilder = new DailySummaryBuilder(detector);
        this.tradingCalendar = new DefaultTradingCalendar(
                FileHolidaySource.fromClasspath(config.holidaysFile()), calendarRepository);

        this.ingestionService = new DailyIngestionService(new LazyCandleDataClient(candleDataClient),
                new SessionSplitter(clock),
                summaryBuilder, new BackfillPlanner(tradingCalendar, instrumentRepository, tradingDayRepository),
                instrumentRepository, tradingDayRepository, gainOpportunityRepository, candleRepository,
                calendarRepository, ingestionRunRepository, tradingCalendar, clock, executor,
                config.intradayStoreRawCandles());
        this.recomputeService = new RecomputeService(instrumentRepository, tradingDayRepository,
                gainOpportunityRepository, candleRepository, summaryBuilder, clock);

        this.eventDetector = new RuleEventDetector(clock);
        this.featureExtractor = new FeatureExtractor(clock);

        IntradaySignalModel baseline = new HotWindowSignalModel(hotWindowRepository, clock,
                config.hotWindowBucketMinutes(), config.hotWindowLookbackDays(),
                config.alertHotWindowMinSessions(), config.alertHotWindowMinLowerBound());
        this.signalModel = config.modelEnabled()
                ? new FallbackSignalModel(new RestIntradaySignalModel(httpClient, config.modelServiceUrl(),
                        Duration.ofSeconds(config.modelTimeoutSeconds())), baseline)
                : baseline;

        ChargeRates rates = new ChargeRates(
                config.chargeRate("brokerage.intraday.pct", 0.05),
                config.chargeRate("brokerage.intraday.max", 20.0),
                config.chargeRate("brokerage.delivery.pct", 0.0),
                config.chargeRate("stt.intraday.sell.pct", 0.025),
                config.chargeRate("stt.delivery.pct", 0.1),
                config.chargeRate("exchange.txn.pct", 0.00297),
                config.chargeRate("sebi.pct", 0.0001),
                config.chargeRate("stamp.duty.buy.pct", 0.003),
                config.chargeRate("gst.pct", 18.0));
        ChargeModel chargeModel = new GrowwChargeModel(rates);
        this.tradeJournalService = new TradeJournalService(tradeRepository, realizedLotRepository,
                pnlPeriodRepository, instrumentRepository, tradingDayRepository, new FifoLotMatcher(),
                chargeModel, new PeriodAggregator(), new PeriodBounds(config.financialYearStartMonth()),
                config.preferBrokerCharges(), config.exchange(), config.segment());
    }

    public AlertEngine alertEngine() {
        List<AlertSink> sinks = new ArrayList<>();
        for (String name : config.alertSinks()) {
            switch (name.toLowerCase()) {
                case "console" -> sinks.add(new ConsoleAlertSink(clock));
                case "macos" -> sinks.add(new MacNotificationSink(AlertSeverity.NOTABLE));
                case "file" -> sinks.add(new FileAlertSink(Path.of(config.alertFilePath())));
                default -> throw new IllegalStateException("Unknown alert sink: " + name);
            }
        }
        return new AlertEngine(
                new AlertPolicy(alertRepository, config.alertCooldownMinutes(),
                        config.alertMaxPerSymbolPerDay(), config.alertMaxPerDay()),
                new CompositeAlertSink(sinks),
                alertRepository);
    }

    public SessionAlertPlanner alertPlanner() {
        return new SessionAlertPlanner(hotWindowRepository, clock, config.hotWindowBucketMinutes(),
                config.hotWindowLookbackDays(), config.alertLeadTimeMinutes(),
                config.alertHotWindowMinSessions(), config.alertHotWindowMinLowerBound());
    }

    public HotWindowCalculator hotWindowCalculator() {
        return new HotWindowCalculator(clock, config.hotWindowBucketMinutes());
    }

    public PredictionEvaluator predictionEvaluator() {
        return new PredictionEvaluator(predictionRepository, candleRepository, config.intradayIntervalMinutes());
    }

    public ReasonAttributor reasonAttributor() {
        return new ReasonAttributor(marketEventRepository, alertRepository, instrumentRepository,
                config.exchange(), config.segment(), config.reasonLookbackMinutes());
    }

    public TradeReasonRepository tradeReasonRepository() {
        return tradeReasonRepository;
    }

    public AccountBalanceRepository accountBalanceRepository() {
        return accountBalanceRepository;
    }

    public CaptureAnalyzer captureAnalyzer() {
        return new CaptureAnalyzer(gainOpportunityRepository, detector.version());
    }

    public Product defaultProduct() {
        return Product.valueOf(config.tradesDefaultProduct());
    }

    public AppConfig config() {
        return config;
    }

    public MarketClock clock() {
        return clock;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    public GrowwAuthenticator authenticator() {
        return authenticator.get();
    }

    public CandleDataClient candleDataClient() {
        return candleDataClient.get();
    }

    public GainOpportunityDetector detector() {
        return detector;
    }

    public DailySummaryBuilder summaryBuilder() {
        return summaryBuilder;
    }

    public TradingCalendar tradingCalendar() {
        return tradingCalendar;
    }

    public DailyIngestionService ingestionService() {
        return ingestionService;
    }

    public RecomputeService recomputeService() {
        return recomputeService;
    }

    public EventDetector eventDetector() {
        return eventDetector;
    }

    public FeatureExtractor featureExtractor() {
        return featureExtractor;
    }

    public IntradaySignalModel signalModel() {
        return signalModel;
    }

    public TradeJournalService tradeJournalService() {
        return tradeJournalService;
    }

    public InstrumentRepository instrumentRepository() {
        return instrumentRepository;
    }

    public TradingDayRepository tradingDayRepository() {
        return tradingDayRepository;
    }

    public GainOpportunityRepository gainOpportunityRepository() {
        return gainOpportunityRepository;
    }

    public HotWindowRepository hotWindowRepository() {
        return hotWindowRepository;
    }

    public MarketEventRepository marketEventRepository() {
        return marketEventRepository;
    }

    public AlertRepository alertRepository() {
        return alertRepository;
    }

    public HeartbeatRepository heartbeatRepository() {
        return heartbeatRepository;
    }

    public TradeRepository tradeRepository() {
        return tradeRepository;
    }

    public RealizedLotRepository realizedLotRepository() {
        return realizedLotRepository;
    }

    public AttributionRepository attributionRepository() {
        return attributionRepository;
    }

    public ExecutorService executor() {
        return executor;
    }

    /** Defers the first credential check to the first actual fetch. */
    private record LazyCandleDataClient(Supplier<CandleDataClient> delegate) implements CandleDataClient {

        @Override
        public com.stockanalyzer.model.StockCandleSeries fetchCandles(
                String symbol, String exchange, String segment,
                LocalDateTime startTime, LocalDateTime endTime, int intervalMinutes) {
            return delegate.get().fetchCandles(symbol, exchange, segment, startTime, endTime, intervalMinutes);
        }
    }

    private static <T> Supplier<T> memoize(Supplier<T> factory) {
        return new Supplier<>() {
            private volatile T value;

            @Override
            public T get() {
                T local = value;
                if (local == null) {
                    synchronized (this) {
                        local = value;
                        if (local == null) {
                            value = local = factory.get();
                        }
                    }
                }
                return local;
            }
        };
    }

    @Override
    public void close() {
        executor.shutdown();
        database.close();
    }
}
