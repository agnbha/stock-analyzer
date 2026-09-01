package com.stockanalyzer.store;

import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trend indicators are arithmetic with stated definitions, so they are checkable. */
class TrendIndicatorViewsTest {

    private static final LocalDate START = LocalDate.of(2026, 6, 1);

    @TempDir
    Path directory;

    private Database database;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        InstrumentRepository instruments = new SqliteInstrumentRepository(database);
        TradingDayRepository days = new SqliteTradingDayRepository(database);

        long rising = instruments.findOrCreate("RISER", "NSE", "CASH");
        long falling = instruments.findOrCreate("FALLER", "NSE", "CASH");

        // 30 sessions each: one climbing a rupee a day, one sliding.
        for (int i = 0; i < 30; i++) {
            LocalDate date = START.plusDays(i);
            days.upsert(rising, summary("RISER", date, 100 + i), "test");
            days.upsert(falling, summary("FALLER", date, 200 - i), "test");
        }
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static DailyGainSummary summary(String symbol, LocalDate date, double close) {
        return new DailyGainSummary(symbol, date, 1, close - 1, close + 1, close - 2, close,
                1000, null, 375, 1780000000L + date.toEpochDay() * 86400L,
                1780000000L + date.toEpochDay() * 86400L + 22500, List.of());
    }

    private Double query(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                if (!rs.next()) {
                    return null;
                }
                double value = rs.getDouble(1);
                return rs.wasNull() ? null : value;
            }
        });
    }

    @Test
    @DisplayName("a moving average stays null until its window is genuinely full")
    void averagesWaitForAFullWindow() {
        assertNull(query("SELECT sma20 FROM v_symbol_trend WHERE symbol='RISER' "
                + "ORDER BY session_date LIMIT 1"), "no 20-day average on day one");
        assertNull(query("SELECT sma50 FROM v_symbol_trend WHERE symbol='RISER' "
                + "ORDER BY session_date DESC LIMIT 1"), "30 sessions cannot make a 50-day average");

        // Day 20 is the first with a full 20-day window: closes 100..119, mean 109.5.
        assertEquals(109.5, query("SELECT sma20 FROM v_symbol_trend WHERE symbol='RISER' "
                + "AND sma20 IS NOT NULL ORDER BY session_date LIMIT 1"), 0.001);
    }

    @Test
    @DisplayName("an unbroken climb pins RSI at 100 and an unbroken slide at 0")
    void rsiReachesItsExtremes() {
        assertEquals(100.0, query("SELECT rsi14 FROM v_symbol_trend WHERE symbol='RISER' "
                + "AND rsi14 IS NOT NULL ORDER BY session_date DESC LIMIT 1"), 0.001);
        assertEquals(0.0, query("SELECT rsi14 FROM v_symbol_trend WHERE symbol='FALLER' "
                + "AND rsi14 IS NOT NULL ORDER BY session_date DESC LIMIT 1"), 0.001);
    }

    @Test
    @DisplayName("trend reads bullish while climbing and bearish while sliding")
    void trendScoreFollowsDirection() {
        assertEquals(2.0, query("SELECT trend_score FROM v_symbol_trend WHERE symbol='RISER' "
                + "AND trend_score IS NOT NULL ORDER BY session_date DESC LIMIT 1"), 0.001);
        assertEquals(0.0, query("SELECT trend_score FROM v_symbol_trend WHERE symbol='FALLER' "
                + "AND trend_score IS NOT NULL ORDER BY session_date DESC LIMIT 1"), 0.001);
    }

    @Test
    @DisplayName("pivots follow the textbook formula from the previous session")
    void pivotsMatchTheFormula() {
        // Second session of RISER. Day one closed at 100, and the fixture sets
        // high = close + 1, low = close - 2, so 101 / 98 / 100.
        // Pivot = (101 + 98 + 100) / 3 = 99.667; R1 = 2P - low; S1 = 2P - high.
        double pivot = query("SELECT pivot FROM v_support_resistance WHERE symbol='RISER' "
                + "AND pivot IS NOT NULL ORDER BY session_date LIMIT 1");
        double r1 = query("SELECT r1 FROM v_support_resistance WHERE symbol='RISER' "
                + "AND pivot IS NOT NULL ORDER BY session_date LIMIT 1");
        double s1 = query("SELECT s1 FROM v_support_resistance WHERE symbol='RISER' "
                + "AND pivot IS NOT NULL ORDER BY session_date LIMIT 1");

        assertEquals((101 + 98 + 100) / 3.0, pivot, 0.001);
        assertEquals(2 * pivot - 98, r1, 0.001);
        assertEquals(2 * pivot - 101, s1, 0.001);
        assertTrue(s1 < pivot && pivot < r1, "support sits below the pivot, resistance above");
    }

    @Test
    @DisplayName("breadth counts how much of the universe rose")
    void breadthCountsAdvancers() {
        // One symbol up, one down, every session after the first.
        assertEquals(50.0, query("SELECT advancing_pct FROM v_market_breadth "
                + "ORDER BY session_date DESC LIMIT 1"), 0.001);
        assertEquals(2.0, query("SELECT symbols FROM v_market_breadth "
                + "ORDER BY session_date DESC LIMIT 1"), 0.001);
    }

    @Test
    @DisplayName("betas average to exactly one against the basket they belong to")
    void betasAverageToOne() {
        // The proxy market is the equal-weighted mean of these symbols, so
        // mean(beta) = Cov(mean(r), r_market) / Var(r_market) = 1 by construction.
        // Any sign or scale error in the covariance breaks this identity.
        Double mean = query("SELECT AVG(beta) FROM v_symbol_beta");

        assertTrue(mean != null, "every symbol gets a beta");
        assertEquals(1.0, mean, 1e-9);
    }

    @Test
    @DisplayName("breadth and beta survive a session whose predecessor arrived later")
    void indicatorsIgnoreIngestOrder() {
        // day_change_pct is written at ingest time and is null when a session
        // was stored before the one preceding it. Breadth derives the change
        // from the closes instead, so it must not care.
        database.inTransaction(connection -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE trading_day SET day_change_pct = NULL");
            }
        });

        assertEquals(50.0, query("SELECT advancing_pct FROM v_market_breadth "
                + "ORDER BY session_date DESC LIMIT 1"), 0.001);
        assertEquals(1.0, query("SELECT AVG(beta) FROM v_symbol_beta"), 1e-9);
    }
}
