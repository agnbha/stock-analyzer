package com.stockanalyzer.store;

import com.stockanalyzer.store.jdbc.SqliteMarketRejectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteMarketRejectRepositoryTest {

    @TempDir
    Path directory;

    private Database database;
    private SqliteMarketRejectRepository rejects;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        rejects = new SqliteMarketRejectRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("a first refusal adds the listing, a repeat renews it and counts it")
    void repeatRenews() {
        rejects.record("ASUTENT", "BSE", "GA001", 1_000);
        rejects.record("ASUTENT", "BSE", "GA001", 5_000);

        List<MarketRejectRepository.Reject> all = rejects.findAll();

        assertEquals(1, all.size());
        MarketRejectRepository.Reject reject = all.getFirst();
        assertEquals(new MarketRejectRepository.Reject("ASUTENT", "BSE", "GA001", 1_000, 5_000, 2), reject);
    }

    @Test
    @DisplayName("the same symbol on two exchanges is two listings")
    void keyedBySymbolAndExchange() {
        rejects.record("SHYAM", "BSE", "GA001", 1_000);
        rejects.record("SHYAM", "NSE", "NO_CANDLES", 2_000);

        assertEquals(2, rejects.findAll().size());
        rejects.clear("SHYAM", "BSE");
        assertEquals(List.of("NSE"), rejects.findAll().stream().map(MarketRejectRepository.Reject::exchange).toList());
    }

    @Test
    @DisplayName("a later reason replaces the earlier one")
    void reasonFollowsTheLatestRefusal() {
        rejects.record("HVL", "BSE", "GA001", 1_000);
        rejects.record("HVL", "BSE", "NO_CANDLES", 2_000);

        assertEquals("NO_CANDLES", rejects.findAll().getFirst().reason());
    }

    @Test
    @DisplayName("clearing a listing that is not there is harmless")
    void clearUnknown() {
        rejects.clear("NONE", "NSE");

        assertTrue(rejects.findAll().isEmpty());
    }
}
