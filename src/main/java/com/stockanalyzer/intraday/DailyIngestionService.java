package com.stockanalyzer.intraday;

import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.model.TradingSession;
import com.stockanalyzer.store.CalendarRepository;
import com.stockanalyzer.store.CandleRepository;
import com.stockanalyzer.store.GainOpportunityRepository;
import com.stockanalyzer.store.IngestionRunRepository;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.TradingDayRepository;
import com.stockanalyzer.util.MarketClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Fetches, analyses and stores whatever sessions are missing for a date range.
 *
 * <p>The nightly run and a multi-month backfill are the same call - the planner
 * decides how much work there is. Fetching and detection happen on the shared
 * executor; every write goes through {@link com.stockanalyzer.store.Database},
 * which serialises them. A symbol that fails is recorded and skipped, never
 * allowed to abort the batch.
 */
public final class DailyIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DailyIngestionService.class);

    private final CandleDataClient candleDataClient;
    private final SessionSplitter sessionSplitter;
    private final DailySummaryBuilder summaryBuilder;
    private final BackfillPlanner planner;
    private final InstrumentRepository instrumentRepository;
    private final TradingDayRepository tradingDayRepository;
    private final GainOpportunityRepository gainOpportunityRepository;
    private final CandleRepository candleRepository;
    private final CalendarRepository calendarRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final TradingCalendar calendar;
    private final MarketClock clock;
    private final ExecutorService executor;
    private final boolean storeRawCandles;

    public DailyIngestionService(CandleDataClient candleDataClient,
                                 SessionSplitter sessionSplitter,
                                 DailySummaryBuilder summaryBuilder,
                                 BackfillPlanner planner,
                                 InstrumentRepository instrumentRepository,
                                 TradingDayRepository tradingDayRepository,
                                 GainOpportunityRepository gainOpportunityRepository,
                                 CandleRepository candleRepository,
                                 CalendarRepository calendarRepository,
                                 IngestionRunRepository ingestionRunRepository,
                                 TradingCalendar calendar,
                                 MarketClock clock,
                                 ExecutorService executor,
                                 boolean storeRawCandles) {
        this.candleDataClient = candleDataClient;
        this.sessionSplitter = sessionSplitter;
        this.summaryBuilder = summaryBuilder;
        this.planner = planner;
        this.instrumentRepository = instrumentRepository;
        this.tradingDayRepository = tradingDayRepository;
        this.gainOpportunityRepository = gainOpportunityRepository;
        this.candleRepository = candleRepository;
        this.calendarRepository = calendarRepository;
        this.ingestionRunRepository = ingestionRunRepository;
        this.calendar = calendar;
        this.clock = clock;
        this.executor = executor;
        this.storeRawCandles = storeRawCandles;
    }

    public IngestionReport ingest(List<String> symbols, String exchange, String segment,
                                  LocalDate from, LocalDate to, int intervalMinutes, String mode) {
        Map<String, List<LocalDate>> missing = planner.plan(symbols, exchange, segment, from, to, intervalMinutes);
        long runId = ingestionRunRepository.start(to, mode, missing.size());

        if (missing.isEmpty()) {
            log.info("Nothing missing between {} and {} for {} symbols", from, to, symbols.size());
            ingestionRunRepository.finish(runId, 0, 0, "OK");
            return new IngestionReport(runId, from, to, 0, 0, List.of(), List.of());
        }

        List<CompletableFuture<IngestionReport.SymbolResult>> futures = missing.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(
                        () -> ingestSymbol(runId, entry.getKey(), exchange, segment, entry.getValue(), intervalMinutes),
                        executor))
                .toList();

        List<IngestionReport.SymbolResult> results = futures.stream().map(CompletableFuture::join).toList();

        List<LocalDate> inferred = inferHolidays(missing, from, to, results);
        int succeeded = (int) results.stream().filter(r -> r.error() == null).count();
        int failed = results.size() - succeeded;
        int sessionsWritten = results.stream().mapToInt(IngestionReport.SymbolResult::sessionsWritten).sum();
        ingestionRunRepository.finish(runId, succeeded, failed, failed == 0 ? "OK" : "PARTIAL");

        return new IngestionReport(runId, from, to, missing.size(), sessionsWritten, results, inferred);
    }

    private IngestionReport.SymbolResult ingestSymbol(long runId, String symbol, String exchange, String segment,
                                                       List<LocalDate> missingDates, int intervalMinutes) {
        LocalDate first = missingDates.getFirst();
        LocalDate last = missingDates.getLast();
        try {
            LocalDateTime start = LocalDateTime.of(first, clock.sessionOpen());
            LocalDateTime end = LocalDateTime.of(last, clock.sessionClose());
            StockCandleSeries series = candleDataClient.fetchCandles(
                    symbol, exchange, segment, start, end, intervalMinutes);

            List<TradingSession> sessions = sessionSplitter.split(series, intervalMinutes);
            if (sessions.isEmpty()) {
                log.debug("No candles returned for {} between {} and {}", symbol, first, last);
                return new IngestionReport.SymbolResult(symbol, 0, 0, Set.of(), null);
            }

            long instrumentId = instrumentRepository.findOrCreate(symbol, exchange, segment);
            Set<LocalDate> datesWithData = new LinkedHashSet<>();
            int sessionsWritten = 0;
            int candlesWritten = 0;
            int latestOpportunityCount = 0;
            for (TradingSession session : sessions) {
                Double previousClose = tradingDayRepository
                        .previousClose(instrumentId, session.sessionDate(), intervalMinutes)
                        .orElse(null);
                DailyGainSummary summary = summaryBuilder.build(session, previousClose);

                long tradingDayId = tradingDayRepository.upsert(instrumentId, summary, "groww");
                gainOpportunityRepository.replace(tradingDayId, summaryBuilder.detectorVersion(),
                        summary.opportunities());
                if (storeRawCandles) {
                    candlesWritten += candleRepository.saveAll(instrumentId, intervalMinutes, session.candles());
                }
                datesWithData.add(session.sessionDate());
                latestOpportunityCount = summary.opportunities().size();
                sessionsWritten++;
            }
            log.info("{}: {} sessions stored, {} opportunities in the latest one",
                    symbol, sessionsWritten, latestOpportunityCount);
            return new IngestionReport.SymbolResult(symbol, sessionsWritten, candlesWritten,
                    Set.copyOf(datesWithData), null);
        } catch (Exception e) {
            log.warn("Failed to ingest {} for {}..{}: {}", symbol, first, last, e.getMessage());
            ingestionRunRepository.recordFailure(runId, symbol, last,
                    e.getClass().getSimpleName(), String.valueOf(e.getMessage()), 1);
            return new IngestionReport.SymbolResult(symbol, 0, 0, Set.of(), e.getMessage());
        }
    }

    /**
     * A weekday on which no symbol returned any data is a holiday the published
     * list did not know about - but only if at least one symbol was actually
     * reached, otherwise this is an outage, not a holiday.
     */
    private List<LocalDate> inferHolidays(Map<String, List<LocalDate>> planned, LocalDate from, LocalDate to,
                                          List<IngestionReport.SymbolResult> results) {
        boolean anyReached = results.stream().anyMatch(r -> r.error() == null);
        boolean anyData = results.stream().anyMatch(r -> !r.datesWithData().isEmpty());
        if (!anyReached || !anyData) {
            return List.of();
        }

        Set<LocalDate> datesWithData = new LinkedHashSet<>();
        results.forEach(result -> datesWithData.addAll(result.datesWithData()));

        List<LocalDate> inferred = new ArrayList<>();
        for (LocalDate date : calendar.tradingDaysBetween(from, to)) {
            boolean askedForEverywhere = planned.values().stream().allMatch(dates -> dates.contains(date));
            if (askedForEverywhere && !datesWithData.contains(date)) {
                calendarRepository.markNonTrading(date, "holiday-inferred");
                inferred.add(date);
                log.info("No data for any symbol on {}; recorded as a non-trading day", date);
            }
        }
        return Collections.unmodifiableList(inferred);
    }
}
