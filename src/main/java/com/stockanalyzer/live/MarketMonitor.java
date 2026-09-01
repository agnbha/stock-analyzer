package com.stockanalyzer.live;

import com.stockanalyzer.alert.AlertEngine;
import com.stockanalyzer.alert.SessionAlertPlanner;
import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.features.EventDetector;
import com.stockanalyzer.features.FeatureExtractor;
import com.stockanalyzer.features.FeatureVector;
import com.stockanalyzer.features.TimeOfDayVolumeProfile;
import com.stockanalyzer.intraday.GainOpportunityDetector;
import com.stockanalyzer.intraday.TradingCalendar;
import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.AlertSeverity;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.model.LiveSnapshot;
import com.stockanalyzer.model.LiveSymbolState;
import com.stockanalyzer.model.MarketEvent;
import com.stockanalyzer.model.ScheduledAlert;
import com.stockanalyzer.model.SignalPrediction;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.model.TradingSession;
import com.stockanalyzer.signal.IntradaySignalModel;
import com.stockanalyzer.signal.SignalRequest;
import com.stockanalyzer.store.AlertRepository;
import com.stockanalyzer.store.HeartbeatRepository;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.LiveCandleRepository;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The market-hours process: polls for new candles every couple of minutes,
 * re-runs the same top-3 detector over the partial day, detects events, scores
 * the model, evaluates alerts, and redraws the view.
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
    private final AlertRepository alertRepository;
    private final HeartbeatRepository heartbeatRepository;
    private final SessionAlertPlanner alertPlanner;
    private final InstrumentRepository instrumentRepository;
    private final LiveCandleRepository liveCandleRepository;
    private final TradingDayRepository tradingDayRepository;
    private final TradingCalendar calendar;
    private final SessionReconciler reconciler;
    private final LiveView view;
    private final MarketClock clock;
    private final ExecutorService executor;
    private final MonitorSettings settings;

    private final Map<String, LiveSessionState> states = new LinkedHashMap<>();
    private final Map<String, EventDetector.PriorSessionContext> priorContexts = new HashMap<>();
    private final Set<String> firedEventKeys = new HashSet<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<Alert> recentAlerts = new ArrayList<>();

    public MarketMonitor(CandleDataClient candleDataClient,
                         GainOpportunityDetector detector,
                         EventDetector eventDetector,
                         FeatureExtractor featureExtractor,
                         IntradaySignalModel signalModel,
                         AlertEngine alertEngine,
                         AlertRepository alertRepository,
                         HeartbeatRepository heartbeatRepository,
                         SessionAlertPlanner alertPlanner,
                         InstrumentRepository instrumentRepository,
                         LiveCandleRepository liveCandleRepository,
                         TradingDayRepository tradingDayRepository,
                         TradingCalendar calendar,
                         SessionReconciler reconciler,
                         LiveView view,
                         MarketClock clock,
                         ExecutorService executor,
                         MonitorSettings settings) {
        this.candleDataClient = candleDataClient;
        this.detector = detector;
        this.eventDetector = eventDetector;
        this.featureExtractor = featureExtractor;
        this.signalModel = signalModel;
        this.alertEngine = alertEngine;
        this.alertRepository = alertRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.alertPlanner = alertPlanner;
        this.instrumentRepository = instrumentRepository;
        this.liveCandleRepository = liveCandleRepository;
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

        settings.symbols().forEach(symbol ->
                states.put(symbol, new LiveSessionState(symbol, settings.intervalMinutes())));
        loadPriorContexts(sessionDate);

        List<ScheduledAlert> plan = alertPlanner.plan(sessionDate, settings.symbols());
        alertRepository.replaceSchedule(sessionDate, plan);
        log.info("Planned {} alerts for {}", plan.size(), sessionDate);

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

    /** One poll: fetch, analyse, alert, render. Package-private so tests can drive it directly. */
    void tick(LocalDate sessionDate) {
        long now = nowEpoch();
        List<String> degradedSymbols = new ArrayList<>();

        List<CompletableFuture<Void>> fetches = settings.symbols().stream()
                .map(symbol -> CompletableFuture.runAsync(() -> {
                    try {
                        fetchInto(symbol, sessionDate, now);
                    } catch (Exception e) {
                        synchronized (degradedSymbols) {
                            degradedSymbols.add(symbol);
                        }
                        log.warn("Poll failed for {}: {}", symbol, e.getMessage());
                    }
                }, executor))
                .toList();
        fetches.forEach(CompletableFuture::join);

        List<LiveSymbolState> symbolStates = new ArrayList<>();
        for (String symbol : settings.symbols()) {
            symbolStates.add(analyse(symbol, sessionDate, now));
        }

        fireDueScheduledAlerts(sessionDate, now);

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

    private void fetchInto(String symbol, LocalDate sessionDate, long now) {
        LiveSessionState state = states.get(symbol);
        long from = Math.max(state.watermark(), clock.sessionOpenEpoch(sessionDate));
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
            fireEventAlerts(sessionDate, symbol, events, now);

            List<FeatureVector> features = featureExtractor.extract(session, context, events);
            predictions = signalModel.score(new SignalRequest(symbol, sessionDate,
                    settings.horizonMinutes(), features));
            fireSignalAlerts(sessionDate, symbol, predictions, now);
        }

        SignalPrediction latest = predictions.stream()
                .max(Comparator.comparingLong(SignalPrediction::tsEpoch))
                .orElse(null);

        Double dayChangePct = null;
        if (context.priorClose() != null && context.priorClose() > 0 && state.lastPrice() > 0) {
            dayChangePct = (state.lastPrice() - context.priorClose()) / context.priorClose() * 100.0;
        }

        stage(symbol, sessionDate, state, now);

        return new LiveSymbolState(symbol, state.allCandles(), state.lastCandleProvisional(now), state.lastPrice(),
                dayChangePct, volumeRatio(state.allCandles()), topSoFar, events, latest,
                projectedReturnPct(latest, completed), state.watermark());
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

    private void fireEventAlerts(LocalDate sessionDate, String symbol, List<MarketEvent> events, long now) {
        for (MarketEvent event : events) {
            String key = sessionDate + "|" + symbol + "|" + event.type() + "|" + event.tsEpoch();
            if (!firedEventKeys.add(key)) {
                continue;
            }
            String message = String.format("%s: %s at %s (strength %.2f)", symbol, event.type(),
                    clock.timeOf(event.tsEpoch()).withSecond(0).withNano(0), event.strength());
            deliver(new Alert(sessionDate, now, symbol, "event." + event.type().name().toLowerCase(),
                    AlertSeverity.NOTABLE, event.type().name(), message, key));
        }
    }

    private void fireSignalAlerts(LocalDate sessionDate, String symbol, List<SignalPrediction> predictions, long now) {
        predictions.stream()
                .filter(p -> p.signal() == SignalPrediction.Signal.ENTRY)
                .filter(p -> p.probability() >= settings.modelMinProbability())
                .max(Comparator.comparingDouble(SignalPrediction::probability))
                .ifPresent(best -> {
                    String key = sessionDate + "|" + symbol + "|signal|" + best.tsEpoch();
                    String message = String.format("%s: entry signal %.2f%s", symbol, best.probability(),
                            best.reason() == null ? "" : " (" + best.reason() + ")");
                    deliver(new Alert(sessionDate, now, symbol, "model.entry", AlertSeverity.URGENT,
                            "Entry signal", message, key));
                });
    }

    private void fireDueScheduledAlerts(LocalDate sessionDate, long now) {
        for (ScheduledAlert scheduled : alertRepository.pendingSchedule(sessionDate)) {
            if (scheduled.fireAtEpoch() > now) {
                continue;
            }
            String key = sessionDate + "|" + scheduled.rule() + "|" + scheduled.symbol() + "|"
                    + scheduled.fireAtEpoch();
            AlertSeverity severity = scheduled.symbol() == null ? AlertSeverity.INFO : AlertSeverity.NOTABLE;
            deliver(new Alert(sessionDate, now, scheduled.symbol(), scheduled.rule(), severity,
                    scheduled.rule(), scheduled.payload(), key));
            alertRepository.markScheduleStatus(scheduled.id(), ScheduledAlert.Status.FIRED);
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
        return System.currentTimeMillis() / 1000;
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
                                  double modelMinProbability,
                                  boolean persistLiveCandles) {
    }
}
