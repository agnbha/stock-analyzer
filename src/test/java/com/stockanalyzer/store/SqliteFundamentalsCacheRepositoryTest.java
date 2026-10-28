package com.stockanalyzer.store;

import com.stockanalyzer.canslim.CompanyFundamentals;
import com.stockanalyzer.canslim.ScreenerRatios;
import com.stockanalyzer.store.jdbc.SqliteFundamentalsCacheRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.YearMonth;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteFundamentalsCacheRepositoryTest {

    @TempDir
    Path directory;

    private Database database;
    private SqliteFundamentalsCacheRepository cache;
    private long instrument;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        cache = new SqliteFundamentalsCacheRepository(database);
        instrument = new SqliteInstrumentRepository(database).findOrCreate("ITC", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("figures and both quarters come back as stored")
    void roundTrip() {
        CompanyFundamentals stored = new CompanyFundamentals(Map.of(ScreenerRatios.ROE, 29.3),
                YearMonth.of(2026, 6), YearMonth.of(2026, 9));

        cache.upsert(instrument, 1_000L, stored);

        FundamentalsCacheRepository.Cached found = cache.find(instrument).orElseThrow();
        assertEquals(1_000L, found.fetchedAtEpoch());
        assertEquals(stored, found.fundamentals());
    }

    @Test
    @DisplayName("a page with nothing on it is kept as empty, so it is not mistaken for a miss")
    void emptyIsRemembered() {
        cache.upsert(instrument, 5L, CompanyFundamentals.NONE);

        FundamentalsCacheRepository.Cached found = cache.find(instrument).orElseThrow();
        assertTrue(found.fundamentals().isEmpty());
        assertNull(found.fundamentals().resultsQuarter());
    }

    @Test
    @DisplayName("a row written before quarters were recorded reads back with none")
    void rowFromBeforeQuarters() {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO screener_fundamentals (instrument_id, fetched_at, ratios_json) VALUES (?,?,?)")) {
                insert.setLong(1, instrument);
                insert.setLong(2, 9L);
                insert.setString(3, "{\"Return on equity\":29.3}");
                insert.executeUpdate();
            }
        });

        CompanyFundamentals found = cache.find(instrument).orElseThrow().fundamentals();
        assertEquals(29.3, found.ratios().get(ScreenerRatios.ROE));
        assertNull(found.resultsQuarter());
        assertNull(found.holdingQuarter());
    }

    @Test
    @DisplayName("storing again replaces the earlier copy")
    void replaces() {
        cache.upsert(instrument, 1L, new CompanyFundamentals(Map.of(ScreenerRatios.ROE, 1.0),
                YearMonth.of(2026, 3), null));
        cache.upsert(instrument, 2L, new CompanyFundamentals(Map.of(ScreenerRatios.ROE, 2.0),
                YearMonth.of(2026, 6), null));

        FundamentalsCacheRepository.Cached found = cache.find(instrument).orElseThrow();
        assertEquals(2L, found.fetchedAtEpoch());
        assertEquals(YearMonth.of(2026, 6), found.fundamentals().resultsQuarter());
    }
}
