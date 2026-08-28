package com.stockanalyzer.store;

import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.store.jdbc.SqliteGainOpportunityRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaAndIdempotencyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 27);
    private static final String DETECTOR = "topk-nonoverlap/highlow/v1";

    @TempDir
    Path directory;

    private Database database;
    private InstrumentRepository instruments;
    private TradingDayRepository tradingDays;
    private GainOpportunityRepository opportunities;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instruments = new SqliteInstrumentRepository(database);
        tradingDays = new SqliteTradingDayRepository(database);
        opportunities = new SqliteGainOpportunityRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("migration runs from empty and is safe to repeat")
    void migrationIsRepeatable() {
        new SchemaMigrator(database).migrate();

        assertTrue(instruments.findAll().isEmpty());
    }

    @Test
    @DisplayName("the same instrument resolves to one id however often it is seen")
    void instrumentsAreDeduplicated() {
        long first = instruments.findOrCreate("RELIANCE", "NSE", "CASH");
        long second = instruments.findOrCreate("RELIANCE", "NSE", "CASH");

        assertEquals(first, second);
        assertEquals(1, instruments.findAll().size());
    }

    @Test
    @DisplayName("ingesting the same day twice leaves exactly one session and three windows")
    void ingestingTwiceConverges() {
        long instrumentId = instruments.findOrCreate("RELIANCE", "NSE", "CASH");

        for (int run = 0; run < 2; run++) {
            long tradingDayId = tradingDays.upsert(instrumentId, summary(), "groww");
            opportunities.replace(tradingDayId, DETECTOR, threeWindows());
        }

        assertEquals(1, tradingDays.findRange(instrumentId, 1, DAY, DAY).size());
        long tradingDayId = tradingDays.findId(instrumentId, DAY, 1).orElseThrow();
        assertEquals(3, opportunities.findByTradingDay(tradingDayId, DETECTOR).size());
        assertEquals(3, opportunities.findRange(DAY, DAY, DETECTOR, null).size());
    }

    @Test
    @DisplayName("a different detector version writes a parallel series instead of overwriting")
    void detectorVersionsCoexist() {
        long instrumentId = instruments.findOrCreate("RELIANCE", "NSE", "CASH");
        long tradingDayId = tradingDays.upsert(instrumentId, summary(), "groww");

        opportunities.replace(tradingDayId, DETECTOR, threeWindows());
        opportunities.replace(tradingDayId, "topk-nonoverlap/closeclose/v1", threeWindows().subList(0, 1));

        assertEquals(3, opportunities.findByTradingDay(tradingDayId, DETECTOR).size());
        assertEquals(1, opportunities.findByTradingDay(tradingDayId, "topk-nonoverlap/closeclose/v1").size());
    }

    @Test
    @DisplayName("stored session dates are what the backfill planner subtracts")
    void storedDatesAreQueryable() {
        long instrumentId = instruments.findOrCreate("RELIANCE", "NSE", "CASH");
        tradingDays.upsert(instrumentId, summary(), "groww");

        assertEquals(java.util.Set.of(DAY),
                tradingDays.storedSessionDates(instrumentId, 1, DAY.minusDays(5), DAY));
        assertTrue(tradingDays.storedSessionDates(instrumentId, 5, DAY.minusDays(5), DAY).isEmpty(),
                "a different candle interval is a different series");
    }

    private static DailyGainSummary summary() {
        return new DailyGainSummary("RELIANCE", DAY, 1, 100, 110, 95, 105, 10_000,
                1.5, 375, 1756000000L, 1756022500L, threeWindows());
    }

    private static List<GainOpportunity> threeWindows() {
        return List.of(
                new GainOpportunity(1, 1756000000L, 1756002000L, 95, 110, 15.78, 33),
                new GainOpportunity(2, 1756004000L, 1756005000L, 100, 104, 4.0, 16),
                new GainOpportunity(3, 1756008000L, 1756009000L, 101, 103, 1.98, 16));
    }
}
