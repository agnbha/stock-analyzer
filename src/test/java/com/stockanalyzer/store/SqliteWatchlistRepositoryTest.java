package com.stockanalyzer.store;

import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import com.stockanalyzer.store.jdbc.SqliteWatchlistRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteWatchlistRepositoryTest {

    private static final LocalDate START = LocalDate.of(2026, 6, 1);

    @TempDir
    Path directory;

    private Database database;
    private InstrumentRepository instruments;
    private TradingDayRepository days;
    private SqliteWatchlistRepository watchlist;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instruments = new SqliteInstrumentRepository(database);
        days = new SqliteTradingDayRepository(database);
        watchlist = new SqliteWatchlistRepository(database);

        long riser = instruments.findOrCreate("RISER", "NSE", "CASH");
        long faller = instruments.findOrCreate("FALLER", "NSE", "CASH");
        // 30 sessions: RISER climbs a rupee a day, FALLER slides.
        for (int i = 0; i < 30; i++) {
            days.upsert(riser, summary("RISER", START.plusDays(i), 100 + i), "test");
            days.upsert(faller, summary("FALLER", START.plusDays(i), 200 - i), "test");
        }
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static DailyGainSummary summary(String symbol, LocalDate date, double close) {
        long open = 1780000000L + date.toEpochDay() * 86400L;
        return new DailyGainSummary(symbol, date, 1, close - 1, close + 1, close - 2, close,
                1000, null, 375, open, open + 22500, List.of());
    }

    @Test
    @DisplayName("only symbols reading bullish are returned: above the 20-day average with the 5-day above it too")
    void bullishOnly() {
        assertEquals(Set.of("RISER"), watchlist.bullishSymbols(START.plusDays(30)));
    }

    @Test
    @DisplayName("a session on or after the cut-off is ignored, so today's partial day cannot change the set")
    void laterSessionsAreIgnored() {
        long faller = instruments.findOrCreate("FALLER", "NSE", "CASH");
        // A violent last session lifts FALLER above both averages.
        days.upsert(faller, summary("FALLER", START.plusDays(30), 1000), "test");

        assertEquals(Set.of("RISER"), watchlist.bullishSymbols(START.plusDays(30)));
        assertEquals(Set.of("RISER", "FALLER"), watchlist.bullishSymbols(START.plusDays(31)));
    }

    @Test
    @DisplayName("a symbol without 20 sessions of history is neither bullish nor bearish, so it is left out")
    void thinHistoryIsLeftOut() {
        long newcomer = instruments.findOrCreate("NEWCOMER", "NSE", "CASH");
        for (int i = 0; i < 5; i++) {
            days.upsert(newcomer, summary("NEWCOMER", START.plusDays(25 + i), 100 + i * 10), "test");
        }

        assertEquals(Set.of("RISER"), watchlist.bullishSymbols(START.plusDays(30)));
    }

    @Test
    @DisplayName("holdings are the broker's holdings plus the journal's long open positions")
    void heldFromBothSources() {
        long faller = instruments.findOrCreate("FALLER", "NSE", "CASH");
        long flat = instruments.findOrCreate("FLAT", "NSE", "CASH");
        long unmatchedSell = instruments.findOrCreate("UNMATCHED", "NSE", "CASH");
        database.inTransaction(connection -> {
            try (PreparedStatement holding = connection.prepareStatement("""
                    INSERT INTO account_holding
                      (session_date, symbol, quantity, average_price, last_price, market_value, unrealised, recorded_at)
                    VALUES ('2026-10-28', 'NIFTYBEES', 38, 256.99, 258.35, 9817.3, 51.68, 1)""")) {
                holding.executeUpdate();
            }
            try (PreparedStatement position = connection.prepareStatement("""
                    INSERT INTO open_position (instrument_id, product, quantity, avg_cost, opened_ts, last_updated)
                    VALUES (?, 'CNC', ?, 100, 1, 1)""")) {
                position.setLong(1, faller);
                position.setInt(2, 257);
                position.executeUpdate();
                position.setLong(1, flat);
                position.setInt(2, 0);
                position.executeUpdate();
                position.setLong(1, unmatchedSell);
                position.setInt(2, -150);
                position.executeUpdate();
            }
        });

        assertEquals(Set.of("NIFTYBEES", "FALLER"), watchlist.heldSymbols());
    }

    @Test
    @DisplayName("nothing held is an empty set, not an error")
    void nothingHeld() {
        assertEquals(Set.of(), watchlist.heldSymbols());
    }
}
