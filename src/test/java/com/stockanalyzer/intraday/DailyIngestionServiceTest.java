package com.stockanalyzer.intraday;

import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.client.GrowwApiException;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.store.CalendarRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.GainOpportunityRepository;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.TradingDayRepository;
import com.stockanalyzer.store.jdbc.SqliteCalendarRepository;
import com.stockanalyzer.store.jdbc.SqliteCandleRepository;
import com.stockanalyzer.store.jdbc.SqliteGainOpportunityRepository;
import com.stockanalyzer.store.jdbc.SqliteIngestionRunRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyIngestionServiceTest {

    private static final LocalDate DAY = Candles.DAY;
    private static final String DETECTOR = "topk-nonoverlap/closeclose/v1";

    @TempDir
    Path directory;

    private Database database;
    private InstrumentRepository instruments;
    private TradingDayRepository tradingDays;
    private GainOpportunityRepository opportunities;
    private CalendarRepository calendarRepository;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instruments = new SqliteInstrumentRepository(database);
        tradingDays = new SqliteTradingDayRepository(database);
        opportunities = new SqliteGainOpportunityRepository(database);
        calendarRepository = new SqliteCalendarRepository(database);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        database.close();
    }

    @Test
    @DisplayName("stores a session with its top windows, and one bad symbol does not abort the batch")
    void isolatesFailuresPerSymbol() {
        DailyIngestionService service = service(new FakeCandleClient());

        IngestionReport report = service.ingest(List.of("GOOD", "BAD"), "NSE", "CASH", DAY, DAY, 1, "daily");

        assertEquals(1, report.succeeded());
        assertEquals(1, report.failures().size());
        assertEquals("BAD", report.failures().getFirst().symbol());

        long instrumentId = instruments.findOrCreate("GOOD", "NSE", "CASH");
        assertTrue(tradingDays.findId(instrumentId, DAY, 1).isPresent(), "the good symbol still landed");
        assertEquals(3, opportunities.findRange(DAY, DAY, DETECTOR, "GOOD").size());
    }

    @Test
    @DisplayName("re-running an already ingested day is a no-op")
    void secondRunDoesNothing() {
        FakeCandleClient client = new FakeCandleClient();
        DailyIngestionService service = service(client);

        service.ingest(List.of("GOOD"), "NSE", "CASH", DAY, DAY, 1, "daily");
        int callsAfterFirstRun = client.calls;
        IngestionReport second = service.ingest(List.of("GOOD"), "NSE", "CASH", DAY, DAY, 1, "daily");

        assertEquals(callsAfterFirstRun, client.calls, "nothing was missing, so nothing was fetched");
        assertEquals(0, second.sessionsWritten());
        assertEquals(3, opportunities.findRange(DAY, DAY, DETECTOR, "GOOD").size(), "still three windows");
    }

    @Test
    @DisplayName("a forced run re-fetches a day that is already stored")
    void forcedRunRefetchesStoredDays() {
        FakeCandleClient client = new FakeCandleClient();
        DailyIngestionService service = service(client);

        service.ingest(List.of("GOOD"), "NSE", "CASH", DAY, DAY, 1, "daily");
        int callsAfterFirstRun = client.calls;

        IngestionReport forced =
                service.ingest(List.of("GOOD"), "NSE", "CASH", DAY, DAY, 1, "daily", true);

        assertTrue(client.calls > callsAfterFirstRun, "force means fetch it again");
        assertEquals(1, forced.sessionsWritten());
        assertEquals(3, opportunities.findRange(DAY, DAY, DETECTOR, "GOOD").size(),
                "and the day still holds exactly three windows afterwards");
    }

    @Test
    @DisplayName("reconciliation replaces a session ingested while the market was open")
    void reconciliationOverwritesAPartialDay() {
        PartialThenFullClient client = new PartialThenFullClient();
        DailyIngestionService service = service(client);

        // Mid-session run stores a partial day.
        service.ingest(List.of("GOOD"), "NSE", "CASH", DAY, DAY, 1, "daily");
        long instrumentId = instruments.findOrCreate("GOOD", "NSE", "CASH");
        assertEquals(4, tradingDays.find(instrumentId, DAY, 1).orElseThrow().candleCount());

        // What SessionReconciler does at the close.
        client.marketClosed = true;
        service.ingest(List.of("GOOD"), "NSE", "CASH", DAY, DAY, 1, "live-reconcile", true);

        assertEquals(9, tradingDays.find(instrumentId, DAY, 1).orElseThrow().candleCount(),
                "the complete session replaced the partial one");
    }

    /** Returns half a session first, then the whole thing once the market has closed. */
    private static final class PartialThenFullClient implements CandleDataClient {
        private boolean marketClosed;

        @Override
        public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                               LocalDateTime start, LocalDateTime end, int intervalMinutes) {
            List<Candle> full = Candles.ofCloses(0, 100, 90, 130, 120, 100, 125, 124, 110, 118);
            return new StockCandleSeries(symbol, exchange, segment,
                    marketClosed ? full : full.subList(0, 4));
        }
    }

    @Test
    @DisplayName("a day with no data for any symbol is recorded as non-trading")
    void infersHolidays() {
        // GOOD returns data for DAY only; the planner also wants the day before.
        LocalDate previous = LocalDate.of(2026, 8, 26);
        DailyIngestionService service = service(new FakeCandleClient());

        service.ingest(List.of("GOOD"), "NSE", "CASH", previous, DAY, 1, "backfill");

        Set<LocalDate> nonTrading = calendarRepository.nonTradingDays(previous, DAY);
        assertTrue(nonTrading.contains(previous), "no candles came back for that date");
        assertFalse(nonTrading.contains(DAY));
    }

    @Test
    @DisplayName("an outage is not mistaken for a holiday")
    void totalFailureIsNotAHoliday() {
        DailyIngestionService service = service(new AlwaysFailingClient());

        IngestionReport report = service.ingest(List.of("GOOD", "BAD"), "NSE", "CASH", DAY, DAY, 1, "daily");

        assertEquals(List.of(), report.inferredHolidays());
        assertTrue(calendarRepository.nonTradingDays(DAY, DAY).isEmpty());
    }

    private DailyIngestionService service(CandleDataClient client) {
        MarketClock clock = MarketClock.nse();
        TradingCalendar calendar = new DefaultTradingCalendar(new HolidaySource() {
            @Override
            public Set<LocalDate> holidays() {
                return Set.of();
            }

            @Override
            public Optional<LocalDate> coveredUntil() {
                return Optional.empty();
            }
        }, calendarRepository);

        return new DailyIngestionService(client, new SessionSplitter(clock),
                new DailySummaryBuilder(new TopKNonOverlappingDetector(3, PriceBasis.CLOSE_CLOSE, 1, 0.0)),
                new BackfillPlanner(calendar, instruments, tradingDays),
                instruments, tradingDays, opportunities, new SqliteCandleRepository(database),
                calendarRepository, new SqliteIngestionRunRepository(database), calendar, clock, executor, true);
    }

    /** Returns a rising-then-falling session for GOOD and fails for anything else. */
    private static final class FakeCandleClient implements CandleDataClient {
        private int calls;

        @Override
        public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                               LocalDateTime start, LocalDateTime end, int intervalMinutes) {
            calls++;
            if (!"GOOD".equals(symbol)) {
                throw new GrowwApiException("no such symbol " + symbol, 404);
            }
            List<Candle> candles = Candles.ofCloses(0,
                    100, 90, 130, 120, 100, 125, 124, 110, 118);
            return new StockCandleSeries(symbol, exchange, segment, candles);
        }
    }

    private static final class AlwaysFailingClient implements CandleDataClient {
        @Override
        public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                               LocalDateTime start, LocalDateTime end, int intervalMinutes) {
            throw new GrowwApiException("upstream down", 503);
        }
    }
}
