package com.stockanalyzer.marketwatch;

import com.stockanalyzer.canslim.DailyBar;
import com.stockanalyzer.markets.MarketDataException;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteDailyBarRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteMarketWatchRepository;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
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
    private static final LocalDate SCAN_START = LocalDate.of(2025, 9, 22);

    @TempDir
    Path directory;

    private Database database;
    private SqliteMarketWatchRepository repository;
    private SqliteInstrumentRepository instruments;
    private SqliteDailyBarRepository dailyBars;
    private final Map<String, List<String>> nseLists = new LinkedHashMap<>();
    private final List<String> asked = new ArrayList<>();
    private final Set<String> refused = new HashSet<>();
    /** Stocks the broker has nothing for before the bars already held: listed since. */
    private final Set<String> listedLater = new HashSet<>();
    private final Map<String, List<DailyBar>> indexBars = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        repository = new SqliteMarketWatchRepository(database);
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
        }, index -> "nse:" + index, (symbol, exchange, from, to) -> {
            asked.add(symbol + "@" + exchange + " " + from + ".." + (to == null ? "latest" : to));
            if (refused.contains(symbol)) {
                throw new IllegalStateException("refused");
            }
            if (indexBars.containsKey(symbol)) {
                return indexBars.get(symbol);
            }
            if (to != null && listedLater.contains(symbol)) {
                return List.of();
            }
            return List.of(bar(from.plusDays(1)), bar(to == null ? LATEST : to));
        }, repository, instruments, dailyBars, MarketClock.nse(), CLOCK, "NSE", "CASH", HISTORY_DAYS);
    }

    private static DailyBar bar(LocalDate date) {
        return new DailyBar(date, 100, 101, 99, 100, 1000);
    }

    private void holdBars(String symbol, LocalDate... dates) {
        long id = instruments.findOrCreate(symbol, "NSE", "CASH");
        dailyBars.upsert(id, java.util.Arrays.stream(dates).map(MarketWatchServiceTest::bar).toList(),
                b -> MarketClock.nse().sessionOpenEpoch(b.date()));
    }

    private static MarketWatchService.Index index(String name, String... members) {
        return new MarketWatchService.Index(name, List.of(members), null, null);
    }

    @Test
    @DisplayName("each index's members from NSE or the settings, in the order given; a list that fails keeps the old one")
    void readsTheMembers() {
        repository.replace("NIFTY B", 0, List.of("DDD"), "NSE", "nse:NIFTY B", 1);
        repository.replace("OLD INDEX", 1, List.of("EEE"), "NSE", "nse:OLD INDEX", 1);
        nseLists.put("NIFTY A", List.of("AAA", "BBB"));

        MarketWatchService.Report report = service().refresh(List.of(index("NIFTY A"), index("SENSEX", "BBB", "CCC"),
                index("NIFTY B")), false);

        assertEquals(List.of(
                new MarketWatchService.IndexResult("NIFTY A", 2, "nse:NIFTY A", null),
                new MarketWatchService.IndexResult("SENSEX", 2, "settings", null),
                new MarketWatchService.IndexResult("NIFTY B", 1, null, "NSE has no list for NIFTY B")), report.indices());
        assertEquals(1, report.dropped(), "the index no longer followed");
        assertNull(report.bars(), "bars left alone when not asked for");
        assertTrue(asked.isEmpty());
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("NIFTY A", 2);
        expected.put("SENSEX", 2);
        expected.put("NIFTY B", 1);
        assertEquals(expected, repository.counts(), "in the order given, the failed one moved to its new place");
    }

    @Test
    @DisplayName("bars are read for the span before a stock's first bar, for members and the market; up to today for a member behind")
    void readsTheBarsStocksLack() {
        nseLists.put("NIFTY A", List.of("FULL", "SHORT", "BEHIND", "NONE", "BROKEN"));
        holdBars("FULL", FROM.plusDays(MarketWatchService.REACH_SLACK_DAYS), LATEST);
        holdBars("SHORT", SCAN_START, LATEST);
        holdBars("BEHIND", FROM, LocalDate.of(2026, 11, 20));
        holdBars("BROKEN", LocalDate.of(2026, 1, 5), LATEST);
        refused.add("BROKEN");
        holdBars("MARKET", SCAN_START, LATEST);
        holdBars("MARKETBEHIND", FROM, LocalDate.of(2026, 11, 20));
        holdBars("DELISTED", SCAN_START, LocalDate.of(2026, 11, 1));

        MarketWatchService.BarsResult bars = service().refresh(List.of(index("NIFTY A")), true).bars();

        assertEquals(List.of(
                "BEHIND@NSE 2026-11-20..latest",
                "BROKEN@NSE 2024-10-25..2026-01-04",
                "NONE@NSE 2024-10-25..latest",
                "SHORT@NSE 2024-10-25..2025-09-21",
                "MARKET@NSE 2024-10-25..2025-09-21"), asked,
                "members first; FULL reaches back; a stock of the market behind is the scan's to price; one with no bar "
                        + "in ten days is no longer one of the market's");
        assertEquals(7, bars.stocks(), "five members and the two of the market's with a recent bar");
        assertEquals(4, bars.read());
        assertEquals(List.of("BROKEN: refused"), bars.failures());
        assertFalse(bars.stopped());
        long none = instruments.findOrCreate("NONE", "NSE", "CASH");
        assertEquals(List.of(FROM.plusDays(1), LATEST), dailyBars.find(none).stream().map(DailyBar::date).toList(),
                "a stock not held before is added with its bars");
        long market = instruments.findOrCreate("MARKET", "NSE", "CASH");
        assertEquals(List.of(FROM.plusDays(1), SCAN_START.minusDays(1), SCAN_START, LATEST),
                dailyBars.find(market).stream().map(DailyBar::date).toList());
    }

    @Test
    @DisplayName("a stock listed after the span starts is asked once, not every night")
    void remembersHowFarBackItAsked() {
        nseLists.put("NIFTY A", List.of("NEW"));
        holdBars("NEW", SCAN_START, LATEST);
        listedLater.add("NEW");

        service().refresh(List.of(index("NIFTY A")), true);
        service().refresh(List.of(index("NIFTY A")), true);

        assertEquals(List.of("NEW@NSE 2024-10-25..2025-09-21"), asked, "the second night asks nothing");
    }

    @Test
    @DisplayName("ten failures in a row leave the rest for another night")
    void stopsWhenTheBrokerRefuses() {
        List<String> symbols = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            symbols.add("S" + (char) ('A' + i));
        }
        nseLists.put("NIFTY A", symbols);
        refused.addAll(symbols);

        MarketWatchService.BarsResult bars = service().refresh(List.of(index("NIFTY A")), true).bars();

        assertEquals(MarketWatchService.MAX_FAILURES_IN_A_ROW, asked.size());
        assertEquals(0, bars.read());
        assertTrue(bars.stopped());
    }

    @Test
    @DisplayName("each index's own bars are stored with their 14-day RSI; one that cannot be read does not stop the rest")
    void indexBarsAndRsi() {
        List<DailyBar> nifty = new ArrayList<>();
        List<Double> closes = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 10, 1);
        for (int i = 0; i < 30; i++) {
            double close = 100 + 5 * Math.sin(i / 3.0) + i * 0.2;
            closes.add(close);
            nifty.add(new DailyBar(day.plusDays(i), close, close + 1, close - 1, close, 0));
        }
        indexBars.put("NIFTY", nifty);
        refused.add("SENSEX");

        MarketWatchService.Report report = service().refresh(List.of(
                new MarketWatchService.Index("NIFTY 50", List.of("AAA"), "NIFTY", "NSE"),
                new MarketWatchService.Index("SENSEX", List.of("BBB"), "SENSEX", "BSE"),
                index("NIFTY NONE", "CCC")), true);

        List<Double> expected = Rsi.wilder(closes, 14);
        assertEquals(List.of(
                new MarketWatchService.IndexBarsResult("NIFTY 50", 30, day.plusDays(29), expected.getLast(), null),
                new MarketWatchService.IndexBarsResult("SENSEX", 0, null, null, "refused")), report.indexBars(),
                "an index without a symbol has no bars to read");
        assertTrue(asked.contains("SENSEX@BSE 2024-10-25..latest"), "on its own exchange: " + asked);
        List<Double> stored = database.read(connection -> {
            List<Double> rsi = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT rsi14 FROM index_bar WHERE index_name = 'NIFTY 50' ORDER BY session_date");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    double value = rs.getDouble(1);
                    rsi.add(rs.wasNull() ? null : value);
                }
            }
            return rsi;
        });
        assertEquals(expected, stored);
    }

    @Test
    @DisplayName("the market's breadth is worked out on every run, bars read or not")
    void breadthOnEveryRun() {
        List<DailyBar> rising = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rising.add(new DailyBar(LocalDate.of(2026, 11, 2).plusDays(i), 100 + i, 101 + i, 99 + i, 100 + i, 1000));
        }
        dailyBars.upsert(instruments.findOrCreate("UP", "NSE", "CASH"), rising,
                b -> MarketClock.nse().sessionOpenEpoch(b.date()));

        MarketWatchService.Report report = service().refresh(List.of(), false);

        assertEquals(3, report.breadthSessions(), "the 10th, 11th and 12th sessions have a 10-day average");
    }
}
