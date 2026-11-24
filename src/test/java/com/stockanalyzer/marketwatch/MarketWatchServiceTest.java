package com.stockanalyzer.marketwatch;

import com.stockanalyzer.canslim.DailyBar;
import com.stockanalyzer.markets.MarketDataException;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteDailyBarRepository;
import com.stockanalyzer.store.jdbc.SqliteIndexMemberRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWatchServiceTest {

    /** 17:30 IST on a Tuesday, after the close. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);
    private static final int HISTORY_DAYS = 760;
    /** What the reads ask from: today, 760 days back. */
    private static final LocalDate FROM = LocalDate.of(2024, 10, 25);
    private static final LocalDate LATEST = LocalDate.of(2026, 11, 24);

    @TempDir
    Path directory;

    private Database database;
    private SqliteIndexMemberRepository members;
    private SqliteInstrumentRepository instruments;
    private SqliteDailyBarRepository dailyBars;
    private final Map<String, List<String>> nseLists = new LinkedHashMap<>();
    private final List<String> barsAskedFor = new ArrayList<>();
    private final Set<String> barsFail = new java.util.HashSet<>();

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        members = new SqliteIndexMemberRepository(database);
        instruments = new SqliteInstrumentRepository(database);
        dailyBars = new SqliteDailyBarRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private MarketWatchService service() {
        return new MarketWatchService(index -> {
            List<String> list = nseLists.get(index);
            if (list == null) {
                throw new MarketDataException("NSE has no list for " + index, null);
            }
            return list;
        }, index -> "nse:" + index, (symbol, exchange, from) -> {
            barsAskedFor.add(symbol + "@" + exchange + " from " + from);
            if (barsFail.contains(symbol)) {
                throw new IllegalStateException("refused");
            }
            return List.of(bar(from.plusDays(1)), bar(LATEST));
        }, members, instruments, dailyBars, MarketClock.nse(), CLOCK, "NSE", "CASH", HISTORY_DAYS);
    }

    private static DailyBar bar(LocalDate date) {
        return new DailyBar(date, 100, 101, 99, 100, 1000);
    }

    private void holdBars(String symbol, LocalDate... dates) {
        long id = instruments.findOrCreate(symbol, "NSE", "CASH");
        dailyBars.upsert(id, java.util.Arrays.stream(dates).map(MarketWatchServiceTest::bar).toList(),
                b -> MarketClock.nse().sessionOpenEpoch(b.date()));
    }

    @Test
    @DisplayName("each index's members from NSE or the settings, in the order given; a list that fails keeps the old one")
    void readsTheMembers() {
        members.replace("NIFTY B", 0, List.of("DDD"), "NSE", "nse:NIFTY B", 1);
        members.replace("OLD INDEX", 1, List.of("EEE"), "NSE", "nse:OLD INDEX", 1);
        nseLists.put("NIFTY A", List.of("AAA", "BBB"));

        MarketWatchService.Report report = service().refresh(List.of(
                new MarketWatchService.Index("NIFTY A", List.of()),
                new MarketWatchService.Index("SENSEX", List.of("BBB", "CCC")),
                new MarketWatchService.Index("NIFTY B", List.of())), false);

        assertEquals(List.of(
                new MarketWatchService.IndexResult("NIFTY A", 2, "nse:NIFTY A", null),
                new MarketWatchService.IndexResult("SENSEX", 2, "settings", null),
                new MarketWatchService.IndexResult("NIFTY B", 1, null, "NSE has no list for NIFTY B")), report.indices());
        assertEquals(1, report.dropped(), "the index no longer followed");
        assertNull(report.bars(), "bars left alone when not asked for");
        assertTrue(barsAskedFor.isEmpty());
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("NIFTY A", 2);
        expected.put("SENSEX", 2);
        expected.put("NIFTY B", 1);
        assertEquals(expected, members.counts(), "in the order given, the failed one moved to its new place");
    }

    @Test
    @DisplayName("a member's bars are read when they do not reach back far enough, stop short of the latest, or are missing")
    void readsTheBarsMembersLack() {
        nseLists.put("NIFTY A", List.of("FULL", "SHORT", "BEHIND", "NONE", "BROKEN"));
        holdBars("FULL", FROM.plusDays(MarketWatchService.REACH_SLACK_DAYS), LATEST);
        holdBars("SHORT", LocalDate.of(2025, 9, 22), LATEST);
        holdBars("BEHIND", FROM, LocalDate.of(2026, 11, 20));
        holdBars("BROKEN", LocalDate.of(2026, 1, 5), LATEST);
        barsFail.add("BROKEN");

        MarketWatchService.BarsResult bars = service()
                .refresh(List.of(new MarketWatchService.Index("NIFTY A", List.of())), true).bars();

        assertEquals(List.of("BEHIND@NSE from " + FROM, "BROKEN@NSE from " + FROM, "NONE@NSE from " + FROM,
                "SHORT@NSE from " + FROM), barsAskedFor, "FULL reaches back far enough and is current");
        assertEquals(5, bars.stocks());
        assertEquals(3, bars.read());
        assertEquals(List.of("BROKEN: refused"), bars.failures());
        assertFalse(bars.stopped());
        long none = instruments.findOrCreate("NONE", "NSE", "CASH");
        assertEquals(List.of(FROM.plusDays(1), LATEST), dailyBars.find(none).stream().map(DailyBar::date).toList(),
                "a stock not held before is added with its bars");
    }

    @Test
    @DisplayName("ten failures in a row leave the rest for another night")
    void stopsWhenTheBrokerRefuses() {
        List<String> symbols = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            symbols.add("S" + (char) ('A' + i));
        }
        nseLists.put("NIFTY A", symbols);
        barsFail.addAll(symbols);

        MarketWatchService.BarsResult bars = service()
                .refresh(List.of(new MarketWatchService.Index("NIFTY A", List.of())), true).bars();

        assertEquals(MarketWatchService.MAX_FAILURES_IN_A_ROW, barsAskedFor.size());
        assertEquals(0, bars.read());
        assertTrue(bars.stopped());
    }
}
