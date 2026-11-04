package com.stockanalyzer.live;

import com.stockanalyzer.alert.AlertEngine;
import com.stockanalyzer.alert.SellTargetAlerts;
import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.features.EventDetector;
import com.stockanalyzer.features.FeatureExtractor;
import com.stockanalyzer.features.FeatureVector;
import com.stockanalyzer.features.TimeOfDayVolumeProfile;
import com.stockanalyzer.intraday.GainOpportunityDetector;
import com.stockanalyzer.intraday.TradingCalendar;
import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.model.LiveSnapshot;
import com.stockanalyzer.model.LiveSymbolState;
import com.stockanalyzer.model.MarketEvent;
import com.stockanalyzer.model.SignalPrediction;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.model.TradingSession;
import com.stockanalyzer.signal.IntradaySignalModel;
import com.stockanalyzer.signal.SignalRequest;
import com.stockanalyzer.store.HeartbeatRepository;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.LiveCandleRepository;
import com.stockanalyzer.store.LiveOpportunityRepository;
import com.stockanalyzer.store.MarketEventRepository;
import com.stockanalyzer.store.TradeRepository;
import com.stockanalyzer.store.TradingDayRepository;
import com.stockanalyzer.util.MarketClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The market-hours process: polls for new candles every couple of minutes,
 * re-runs the same top-3 detector over the partial day, detects events, scores
 * the model, checks the day's purchases against their sell targets, and redraws
 * the view. The sell-target check is the only thing that alerts.
 *
 * <p>Parts 3 and 4 share this one loop deliberately - the poll tick is exactly
 * the moment at which alert conditions can be evaluated, so there is no second
 * scheduler to keep in step.
 */
public final class MarketMonitor {

    private static final Logger log = LoggerFactory.getLogger(MarketMonitor.class);

    private final CandleDataClient candleDataClient;
    private final GainOpportunityDetector detector;
    private final EventDetector eventDetector;
    private final FeatureExtractor featureExtractor;
    private final IntradaySignalModel signalModel;
    private final AlertEngine alertEngine;
    private final SellTargetAlerts sellTargets;
    private final TradeRepository tradeRepository;
    private final HeartbeatRepository heartbeatRepository;
    private final InstrumentRepository instrumentRepository;
    private final LiveCandleRepository liveCandleRepository;
    private final LiveOpportunityRepository liveOpportunityRepository;
    private final MarketEventRepository marketEventRepository;
    private final TradingDayRepository tradingDayRepository;
    private final TradingCalendar calendar;
    private final SessionReconciler reconciler;
    private final LiveView view;
    private final MarketClock clock;
    private final ExecutorService executor;
    private final MonitorSettings settings;
    private final LongSupplier nowSeconds;

    private final Map<String, LiveSessionState> states = new LinkedHashMap<>();
    private final Map<String, EventDetector.PriorSessionContext> priorContexts = new HashMap<>();
    /** Symbols bought today that are not in the tracked list: polled too, so their sell targets can be watched. */
    private final Set<String> untrackedBuys = new LinkedHashSet<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<Alert> recentAlerts = new ArrayList<>();

    public MarketMonitor(CandleDataClient candleDataClient,
                         GainOpportunityDetector detector,
                         EventDetector eventDetector,
                         FeatureExtractor featureExtractor,
                         IntradaySignalModel signalModel,
                         AlertEngine alertEngine,
                         SellTargetAlerts sellTargets,
                         TradeRepository tradeRepository,
                         HeartbeatRepository heartbeatRepository,
                         InstrumentRepository instrumentRepository,
                         LiveCandleRepository liveCandleRepository,
                         LiveOpportunityRepository liveOpportunityRepository,
                         MarketEventRepository marketEventRepository,
                         TradingDayRepository tradingDayRepository,
                         TradingCalendar calendar,
                         SessionReconciler reconciler,
                         LiveView view,
                         MarketClock clock,
                         ExecutorService executor,
                         MonitorSettings settings) {
        this(candleDataClient, detector, eventDetector, featureExtractor, signalModel, alertEngine,
                sellTargets, tradeRepository, heartbeatRepository, instrumentRepository, liveCandleRepository,
                liveOpportunityRepository, marketEventRepository, tradingDayRepository, calendar, reconciler,
                view, clock, executor, settings, () -> System.currentTimeMillis() / 1000);
    }

    /** With an injectable clock, so a test can start the monitor at 10:08 without waiting for it. */
    MarketMonitor(CandleDataClient candleDataClient,
                  GainOpportunityDetector detector,
                  EventDetector eventDetector,
                  FeatureExtractor featureExtractor,
                  IntradaySignalModel signalModel,
                  AlertEngine alertEngine,
                  SellTargetAlerts sellTargets,
                  TradeRepository tradeRepository,
                  HeartbeatRepository heartbeatRepository,
                  InstrumentRepository instrumentRepository,
                  LiveCandleRepository liveCandleRepository,
                  LiveOpportunityRepository liveOpportunityRepository,
                  MarketEventRepository marketEventRepository,
                  TradingDayRepository tradingDayRepository,
                  TradingCalendar calendar,
                  SessionReconciler reconciler,
                  LiveView view,
                  MarketClock clock,
                  ExecutorService executor,
                  MonitorSettings settings,
                  LongSupplier nowSeconds) {
        this.nowSeconds = nowSeconds;
        this.candleDataClient = candleDataClient;
        this.detector = detector;
        this.eventDetector = eventDetector;
        this.featureExtractor = featureExtractor;
        this.signalModel = signalModel;
        this.alertEngine = alertEngine;
        this.sellTargets = sellTargets;
        this.tradeRepository = tradeRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.instrumentRepository = instrumentRepository;
        this.liveCandleRepository = liveCandleRepository;
        this.liveOpportunityRepository = liveOpportunityRepository;
        this.marketEventRepository = marketEventRepository;
        this.tradingDayRepository = tradingDayRepository;
        this.calendar = calendar;
        this.reconciler = reconciler;
        this.view = view;
        this.clock = clock;
        this.executor = executor;
        this.settings = settings;
    }

    public void stop() {
        running.set(false);
    }

    /** Runs from now until shortly after the close, then reconciles. */
    public void run(LocalDate sessionDate) {
        if (!calendar.isTradingDay(sessionDate)) {
            log.info("{} is not a trading day; nothing to monitor", sessionDate);
            return;
        }

        prepare(sessionDate);

        long closeEpoch = clock.sessionCloseEpoch(sessionDate);
        while (running.get() && nowEpoch() <= closeEpoch + settings.postCloseGraceSeconds()) {
            tick(sessionDate);
            sleep(settings.pollIntervalSeconds());
        }

        Map<String, List<GainOpportunity>> liveTop = new LinkedHashMap<>();
        states.forEach((symbol, state) ->
                liveTop.put(symbol, detector.detect(state.completedCandles(nowEpoch()))));
        reconciler.reconcile(sessionDate, settings.symbols(), settings.exchange(), settings.segment(),
                settings.intervalMinutes(), liveTop);
        view.close();
    }

    /** Per-symbol session state and the prior session's context; package-private so a test can set up a tick. */
    void prepare(LocalDate sessionDate) {
        settings.symbols().forEach(symbol ->
                states.put(symbol, new LiveSessionState(symbol, settings.intervalMinutes())));
        loadPriorContexts(sessionDate);
    }

    /** One poll: fetch, analyse, alert, render. Package-private so tests can drive it directly. */
    void tick(LocalDate sessionDate) {
        long now = nowEpoch();
        List<String> degradedSymbols = new ArrayList<>();

        List<Trade> fills = todaysFills(sessionDate);
        Set<String> bought = new LinkedHashSet<>();
        for (Trade fill : fills) {
            if (fill.side() == Side.BUY) {
                bought.add(fill.symbol());
                if (!settings.symbols().contains(fill.symbol()) && untrackedBuys.add(fill.symbol())) {
                    states.put(fill.symbol(), new LiveSessionState(fill.symbol(), settings.intervalMinutes()));
                    log.info("{} was bought today and is not a tracked symbol: polling it for its sell targets",
                            fill.symbol());
                }
            }
        }

        Map<String, List<String>> failuresByCause = new LinkedHashMap<>();
        List<String> toFetch = new ArrayList<>(settings.symbols());
        toFetch.addAll(untrackedBuys);
        List<CompletableFuture<Void>> fetches = toFetch.stream()
                .map(symbol -> CompletableFuture.runAsync(() -> {
                    try {
                        fetchInto(symbol, sessionDate, now);
                    } catch (Exception e) {
                        String cause = e.getMessage() == null ? e.toString() : e.getMessage();
                        synchronized (degradedSymbols) {
                            degradedSymbols.add(symbol);
                            failuresByCause.computeIfAbsent(cause, key -> new ArrayList<>()).add(symbol);
                        }
                    }
                }, executor))
                .toList();
        fetches.forEach(CompletableFuture::join);
        logFailures(failuresByCause);

        List<LiveSymbolState> symbolStates = new ArrayList<>();
        for (String symbol : settings.symbols()) {
            symbolStates.add(analyse(symbol, sessionDate, now));
        }

        fireSellTargets(sessionDate, fills, bought, now);

        long staleness = symbolStates.stream()
                .mapToLong(state -> now - state.lastUpdatedEpoch())
                .max().orElse(0);
        LiveSnapshot snapshot = new LiveSnapshot(sessionDate, now, now + settings.pollIntervalSeconds(),
                Math.max(staleness, 0), !degradedSymbols.isEmpty(),
                degradedSymbols.isEmpty() ? null : degradedSymbols.size() + " symbols not refreshed",
                symbolStates, List.copyOf(recentAlerts));
        heartbeatRepository.beat(sessionDate, symbolStates.size(), !degradedSymbols.isEmpty(),
                degradedSymbols.isEmpty() ? "ok" : "degraded: " + degradedSymbols);
        view.render(snapshot);
    }

    /**
     * One line per distinct cause rather than one per symbol. A blocked token
     * fails every symbol with the identical message, and fifty copies of it per
     * tick buries anything that only went wrong for one stock.
     */
    private void logFailures(Map<String, List<String>> failuresByCause) {
        failuresByCause.forEach((cause, symbols) -> {
            if (symbols.size() == 1) {
                log.warn("Poll failed for {}: {}", symbols.getFirst(), cause);
            } else {
                log.warn("Poll failed for {} of {} symbols ({}{}): {}",
                        symbols.size(), settings.symbols().size(),
                        String.join(", ", symbols.subList(0, Math.min(3, symbols.size()))),
                        symbols.size() > 3 ? ", ..." : "", cause);
            }
        });
    }

    private void fetchInto(String symbol, LocalDate sessionDate, long now) {
        LiveSessionState state = states.get(symbol);
        // From the open, not the watermark: the feed reports a running volume total,
        // so a range that starts mid-session cannot tell how much traded before it.
        long from = clock.sessionOpenEpoch(sessionDate);
        LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochSecond(from), clock.zone());
        LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochSecond(now), clock.zone());
        StockCandleSeries series = candleDataClient.fetchCandles(symbol, settings.exchange(), settings.segment(),
                start, end, settings.intervalMinutes());
        state.merge(series.candles());
    }

    private LiveSymbolState analyse(String symbol, LocalDate sessionDate, long now) {
        LiveSessionState state = states.get(symbol);
        List<Candle> completed = state.completedCandles(now);
        EventDetector.PriorSessionContext context =
                priorContexts.getOrDefault(symbol, EventDetector.PriorSessionContext.none());

        List<GainOpportunity> topSoFar = detector.detect(completed);

        List<MarketEvent> events = List.of();
        List<SignalPrediction> predictions = List.of();
        if (completed.size() >= 2) {
            TradingSession session = new TradingSession(symbol, settings.exchange(), settings.segment(),
                    sessionDate, settings.intervalMinutes(), completed);
            events = eventDetector.detect(session, context);
            persistEvents(symbol, events);

            List<FeatureVector> features = featureExtractor.extract(session, context, events);
            predictions = signalModel.score(new SignalRequest(symbol, sessionDate,
                    settings.horizonMinutes(), features));
        }

        SignalPrediction latest = predictions.stream()
                .max(Comparator.comparingLong(SignalPrediction::tsEpoch))
                .orElse(null);

        Double dayChangePct = null;
        if (context.priorClose() != null && context.priorClose() > 0 && state.lastPrice() > 0) {
            dayChangePct = (state.lastPrice() - context.priorClose()) / context.priorClose() * 100.0;
        }

        stage(symbol, sessionDate, state, now);
        stageOpportunities(symbol, sessionDate, topSoFar);

        return new LiveSymbolState(symbol, state.allCandles(), state.lastCandleProvisional(now), state.lastPrice(),
                dayChangePct, volumeRatio(state.allCandles()), topSoFar, events, latest,
                projectedReturnPct(latest, completed), state.watermark());
    }

    /**
     * Publishes the windows found so far, so the dashboards show a top-3 over
     * the session as it stands rather than over however much of it happened to
     * be ingested. Replaced wholesale each tick, because a later minute can
     * change which windows are best.
     */
    private void stageOpportunities(String symbol, LocalDate sessionDate,
                                    List<GainOpportunity> topSoFar) {
        if (!settings.persistLiveCandles()) {
            return;
        }
        try {
            long instrumentId = instrumentRepository.findOrCreate(symbol, settings.exchange(),
                    settings.segment());
            liveOpportunityRepository.replace(instrumentId, sessionDate, detector.version(), topSoFar);
        } catch (RuntimeException e) {
            log.warn("Could not stage windows for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Stores what was detected. Events raise no alerts, but they are the
     * reasons a trade gets attributed to, model features, and the record of what
     * happened that day. Without them every trade would be UNEXPLAINED afterwards.
     *
     * <p>The repository ignores duplicates, so re-detecting the same event on
     * each tick costs nothing.
     */
    private void persistEvents(String symbol, List<MarketEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        try {
            long instrumentId = instrumentRepository.findOrCreate(symbol, settings.exchange(),
                    settings.segment());
            marketEventRepository.saveAll(instrumentId, events, eventDetector.version());
        } catch (RuntimeException e) {
            log.warn("Could not store events for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Writes the session so far to staging so the dashboards can follow it live.
     * The newest candle is flagged provisional when its interval has not closed,
     * so nothing downstream mistakes a forming candle for a settled one - and
     * none of this reaches the canonical tables, which only the close-time
     * reconciliation writes.
     */
    private void stage(String symbol, LocalDate sessionDate, LiveSessionState state, long now) {
        if (!settings.persistLiveCandles()) {
            return;
        }
        List<Candle> all = state.allCandles();
        if (all.isEmpty()) {
            return;
        }
        long provisionalFrom = state.lastCandleProvisional(now) ? all.getLast().epochSeconds() : 0;
        try {
            long instrumentId = instrumentRepository.findOrCreate(symbol, settings.exchange(),
                    settings.segment());
            liveCandleRepository.upsertAll(instrumentId, sessionDate, settings.intervalMinutes(),
                    all, provisionalFrom);
        } catch (RuntimeException e) {
            // Staging is a convenience for the dashboards; losing a tick of it
            // must never take the monitor down.
            log.warn("Could not stage live candles for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * A projection, not an observation: the model's probability applied to the
     * move the recent range makes plausible over the horizon. Rendered as
     * "proj" and stored only as a prediction that gets scored the next day.
     */
    private Double projectedReturnPct(SignalPrediction latest, List<Candle> completed) {
        if (latest == null || completed.size() < 15) {
            return null;
        }
        double price = completed.getLast().close();
        if (price <= 0) {
            return null;
        }
        double averageRange = completed.subList(completed.size() - 14, completed.size()).stream()
                .mapToDouble(candle -> candle.high() - candle.low())
                .average().orElse(0);
        double expectedMovePct = averageRange / price * 100.0
                * Math.sqrt(settings.horizonMinutes() / (double) settings.intervalMinutes());
        return latest.probability() * expectedMovePct;
    }

    private static double volumeRatio(List<Candle> candles) {
        if (candles.size() < 2) {
            return 1.0;
        }
        double mean = candles.stream().mapToLong(Candle::volume).average().orElse(0);
        return mean <= 0 ? 1.0 : candles.getLast().volume() / mean;
    }

    /** Everything filled today, for any symbol; empty rather than fatal when the journal cannot be read. */
    private List<Trade> todaysFills(LocalDate sessionDate) {
        try {
            return tradeRepository.findRange(sessionDate, sessionDate);
        } catch (RuntimeException e) {
            log.warn("Could not read today's fills, so no sell targets this tick: {}", e.getMessage());
            return List.of();
        }
    }

    /** For each symbol bought today, the sell-target alerts that have come due. */
    private void fireSellTargets(LocalDate sessionDate, List<Trade> fills, Set<String> bought, long now) {
        for (String symbol : bought) {
            LiveSessionState state = states.get(symbol);
            if (state == null) {
                continue;
            }
            for (Alert alert : sellTargets.due(sessionDate, symbol, fills, state.allCandles(), now)) {
                deliver(alert);
            }
        }
    }

    private void deliver(Alert alert) {
        if (alertEngine.fire(alert)) {
            recentAlerts.add(alert);
            while (recentAlerts.size() > 5) {
                recentAlerts.removeFirst();
            }
        }
    }

    private void loadPriorContexts(LocalDate sessionDate) {
        LocalDate previous = calendar.previousTradingDay(sessionDate);
        for (String symbol : settings.symbols()) {
            long instrumentId = instrumentRepository.findOrCreate(symbol, settings.exchange(), settings.segment());
            DailyGainSummary prior = tradingDayRepository
                    .find(instrumentId, previous, settings.intervalMinutes())
                    .orElse(null);
            TimeOfDayVolumeProfile profile = TimeOfDayVolumeProfile.empty();
            priorContexts.put(symbol, prior == null
                    ? new EventDetector.PriorSessionContext(null, null, null, profile)
                    : new EventDetector.PriorSessionContext(prior.close(), prior.high(), prior.low(), profile));
        }
    }

    private long nowEpoch() {
        return nowSeconds.getAsLong();
    }

    private void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    /** Everything the loop needs that is configuration rather than collaboration. */
    public record MonitorSettings(List<String> symbols,
                                  String exchange,
                                  String segment,
                                  int intervalMinutes,
                                  int pollIntervalSeconds,
                                  int postCloseGraceSeconds,
                                  int horizonMinutes,
                                  boolean persistLiveCandles) {
    }
}
