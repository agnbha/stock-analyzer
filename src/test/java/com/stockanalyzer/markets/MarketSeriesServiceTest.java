package com.stockanalyzer.markets;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteMarketSeriesRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the evening read asks FRED for, and what it keeps. The readings are invented. */
class MarketSeriesServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T13:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    private Database database;
    private SqliteMarketSeriesRepository repository;
    private final List<String> asked = new ArrayList<>();
    private final Map<String, List<Observation>> available = new HashMap<>();

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        repository = new SqliteMarketSeriesRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private MarketSeriesService service() {
        return new MarketSeriesService((series, from) -> {
            asked.add(series + " from " + from);
            if (!available.containsKey(series)) {
                throw new MarketDataException("FRED answered HTTP 404 for " + series, null);
            }
            return available.get(series).stream().filter(o -> !o.date().isBefore(from)).toList();
        }, repository, CLOCK);
    }

    private static Observation on(String date, double value) {
        return new Observation(LocalDate.parse(date), value);
    }

    @Test
    @DisplayName("the first read goes back the whole history; later reads go back a month from the newest reading held")
    void readsTheHistoryOnceThenTheLastMonth() {
        available.put("US10Y", List.of(on("2025-01-02", 4.5), on("2026-11-13", 5.0), on("2026-11-16", 5.1)));

        service().refresh(List.of("US10Y"), 760);
        service().refresh(List.of("US10Y"), 760);

        assertEquals(List.of("US10Y from " + TODAY.minusDays(760), "US10Y from 2026-10-16"), asked);
        assertEquals(LocalDate.of(2026, 11, 16), repository.latest("US10Y").orElseThrow());
        assertEquals("3", scalar("SELECT COUNT(*) FROM market_series"), "read twice, held once");
    }

    @Test
    @DisplayName("a reading the source revises replaces the one held; a late oil price fills in its own day")
    void revisionsAndLateReadings() {
        available.put("BRENT", List.of(on("2026-11-10", 91.0), on("2026-11-11", 92.0)));
        service().refresh(List.of("BRENT"), 760);

        available.put("BRENT", List.of(on("2026-11-10", 91.0), on("2026-11-11", 92.5), on("2026-11-12", 93.0)));
        MarketSeriesService.Result result = service().refresh(List.of("BRENT"), 760).getFirst();

        assertEquals(3, result.stored());
        assertEquals(LocalDate.of(2026, 11, 12), result.latest());
        assertEquals("92.5", scalar("SELECT value FROM market_series WHERE observed_on = '2026-11-11'"));
        assertEquals("1795008600", scalar("SELECT MAX(fetched_at) FROM market_series"), "when it was read");
    }

    @Test
    @DisplayName("a series that cannot be read is reported and kept as it was; the others are still read")
    void oneFailureDoesNotStopTheRest() {
        available.put("US2Y", List.of(on("2026-11-16", 4.8)));

        List<MarketSeriesService.Result> results = service().refresh(List.of("NOSUCH", "US2Y"), 760);

        assertTrue(results.getFirst().error().contains("404"), results.getFirst().error());
        assertNull(results.getFirst().latest());
        assertNull(results.get(1).error());
        assertEquals(1, results.get(1).stored());
    }

    private String scalar(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }
}
