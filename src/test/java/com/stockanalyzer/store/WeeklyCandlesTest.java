package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.util.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weekly candles on Candles and Calls, by running the chart's own query: built from the minute-derived sessions
 * where there are some, and from the daily bars where a symbol's minute history has not reached, so a recently
 * added symbol's six months, and a symbol outside the tracked list, are not blank.
 */
class WeeklyCandlesTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");
    // Three Mondays in a row, so each week has its own number.
    private static final LocalDate JUNE_1 = LocalDate.of(2026, 6, 1);
    private static final LocalDate JUNE_8 = LocalDate.of(2026, 6, 8);
    private static final LocalDate JUNE_15 = LocalDate.of(2026, 6, 15);
    private static final LocalDate LONG_AGO = LocalDate.of(2026, 1, 1);

    private static final int TIME = 0;
    private static final int OPEN = 1;
    private static final int HIGH = 2;
    private static final int LOW = 3;
    private static final int CLOSE = 4;
    private static final int VOLUME = 5;
    private static final int BUY = 6;
    private static final int SELL = 7;

    @TempDir
    Path directory;

    private Database database;
    private long young;
    private long old;
    private int fills;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        SqliteInstrumentRepository instruments = new SqliteInstrumentRepository(database);
        young = instruments.findOrCreate("YOUNG", "NSE", "CASH");
        old = instruments.findOrCreate("OLD", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("a week with daily bars only opens on its first bar, closes on its last, and spans them all")
    void dailyBarsOnly() throws Exception {
        bar(young, JUNE_1, 100, 110, 95, 105, 1_000);
        bar(young, JUNE_1.plusDays(1), 105, 120, 100, 118, 2_000);
        bar(young, JUNE_1.plusDays(4), 118, 119, 90, 92, 3_000);

        List<Object[]> weeks = weeks("YOUNG");

        assertEquals(1, weeks.size());
        Object[] week = weeks.getFirst();
        assertEquals(100.0, (double) week[OPEN], "Monday's open");
        assertEquals(120.0, (double) week[HIGH], "the highest high");
        assertEquals(90.0, (double) week[LOW], "the lowest low");
        assertEquals(92.0, (double) week[CLOSE], "Friday's close");
        assertEquals(6_000.0, ((Number) week[VOLUME]).doubleValue(), "the days' volumes added");
        assertEquals(epoch(JUNE_1), ((Number) week[TIME]).longValue(), "the week starts at its first bar");
    }

    @Test
    @DisplayName("a week that starts on daily bars and ends on minute-derived sessions is one candle of both")
    void aWeekOfBothSources() throws Exception {
        bar(young, JUNE_1, 100, 110, 95, 105, 1_000);                     // Monday: only the daily bar
        bar(young, JUNE_1.plusDays(1), 105, 108, 99, 106, 1_500);         // Tuesday: only the daily bar
        session(young, JUNE_1.plusDays(2), 106, 130, 104, 128, 2_000);    // Wednesday on: minute-derived
        session(young, JUNE_1.plusDays(4), 128, 129, 60, 70, 4_000);

        List<Object[]> weeks = weeks("YOUNG");

        assertEquals(1, weeks.size());
        Object[] week = weeks.getFirst();
        assertEquals(100.0, (double) week[OPEN], "Monday's open, from the daily bar");
        assertEquals(130.0, (double) week[HIGH]);
        assertEquals(60.0, (double) week[LOW]);
        assertEquals(70.0, (double) week[CLOSE], "Friday's close, from the session");
        assertEquals(8_500.0, ((Number) week[VOLUME]).doubleValue());
    }

    @Test
    @DisplayName("a day held both ways is counted once, as the minute-derived session: what is drawn does not move")
    void theSessionWinsOverTheBar() throws Exception {
        session(young, JUNE_1, 100, 110, 95, 105, 1_000);
        bar(young, JUNE_1, 99.9, 111, 94, 104.9, 1_001);                  // the same day, a hair different

        Object[] week = weeks("YOUNG").getFirst();

        assertEquals(100.0, (double) week[OPEN]);
        assertEquals(110.0, (double) week[HIGH]);
        assertEquals(95.0, (double) week[LOW]);
        assertEquals(105.0, (double) week[CLOSE]);
        assertEquals(1_000.0, ((Number) week[VOLUME]).doubleValue(), "not 2,001");
    }

    @Test
    @DisplayName("each symbol has its own weeks, and a session of another symbol does not hide this one's bar")
    void symbolsAreKeptApart() throws Exception {
        bar(young, JUNE_1, 100, 110, 95, 105, 1_000);
        session(old, JUNE_1, 50, 55, 48, 52, 9_000);                      // another symbol, the same day
        bar(old, JUNE_8, 52, 60, 51, 59, 9_000);

        assertEquals(1, weeks("YOUNG").size());
        assertEquals(1_000.0, ((Number) weeks("YOUNG").getFirst()[VOLUME]).doubleValue());
        assertEquals(2, weeks("OLD").size());
        assertEquals(9_000.0, ((Number) weeks("OLD").getFirst()[VOLUME]).doubleValue());
        assertTrue(weeks("NOBODY").isEmpty());
    }

    @Test
    @DisplayName("consecutive weeks are separate candles, in time order")
    void separateWeeks() throws Exception {
        bar(young, JUNE_15, 120, 121, 119, 120, 1);
        bar(young, JUNE_1, 100, 101, 99, 100, 1);
        bar(young, JUNE_8, 110, 111, 109, 110, 1);

        List<Object[]> weeks = weeks("YOUNG");

        assertEquals(List.of(100.0, 110.0, 120.0), weeks.stream().map(w -> (double) w[OPEN]).toList());
    }

    @Test
    @DisplayName("the weeks before a symbol's first session are drawn from its daily bars, on one time axis")
    void theEarlyWeeksAreFilledIn() throws Exception {
        bar(young, JUNE_1, 100, 110, 95, 105, 1_000);                     // before its minute history begins
        bar(young, JUNE_8, 105, 112, 100, 110, 1_000);
        session(young, JUNE_15, 110, 125, 108, 120, 1_000);               // the minute history starts here
        session(old, JUNE_1, 50, 55, 48, 52, 1_000);

        List<Object[]> weeks = weeks("YOUNG");

        assertEquals(3, weeks.size(), "all three weeks, not only the one with sessions");
        assertEquals(List.of(100.0, 105.0, 110.0), weeks.stream().map(w -> (double) w[OPEN]).toList());
        assertEquals(List.of(epoch(JUNE_1), epoch(JUNE_8), epoch(JUNE_15)),
                weeks.stream().map(w -> ((Number) w[TIME]).longValue()).toList());
    }

    @Test
    @DisplayName("a symbol with daily bars only, never tracked, still has its weekly chart")
    void anUntrackedSymbol() throws Exception {
        bar(old, JUNE_1, 50, 55, 48, 52, 1_000);
        bar(old, JUNE_8, 52, 60, 51, 59, 1_000);

        assertEquals(2, weeks("OLD").size());
    }

    @Test
    @DisplayName("the buy and sell dots sit on the weeks they happened in, including a week drawn from daily bars")
    void theFillsAreKept() throws Exception {
        bar(young, JUNE_1, 100, 110, 95, 105, 1_000);
        bar(young, JUNE_8, 105, 112, 100, 110, 1_000);
        trade(young, "BUY", JUNE_1.plusDays(1), 100.0);
        trade(young, "BUY", JUNE_1.plusDays(2), 102.0);
        trade(young, "SELL", JUNE_8.plusDays(1), 111.0);
        trade(old, "BUY", JUNE_1, 5.0);                                   // another symbol's fill

        List<Object[]> weeks = weeks("YOUNG");

        assertEquals(101.0, (double) weeks.get(0)[BUY], "the week's fills averaged");
        assertNull(weeks.get(0)[SELL]);
        assertNull(weeks.get(1)[BUY]);
        assertEquals(111.0, (double) weeks.get(1)[SELL]);
    }

    @Test
    @DisplayName("only the last six months the panel asks for are returned, to the week")
    void onlyTheWindowAsked() throws Exception {
        bar(young, JUNE_1, 100, 110, 95, 105, 1_000);
        bar(young, JUNE_8, 105, 112, 100, 110, 1_000);
        bar(young, JUNE_15, 110, 125, 108, 120, 1_000);

        List<Object[]> weeks = panel("YOUNG", JUNE_8.plusDays(2));        // asks from a Wednesday of June 8's week

        assertEquals(List.of(105.0, 110.0), weeks.stream().map(w -> (double) w[OPEN]).toList());
    }

    @Test
    @DisplayName("the query narrows to the symbol inside its sources, not over every symbol's daily bars")
    void itDoesNotScanTheWholeMarket() throws Exception {
        bar(young, JUNE_1, 100, 110, 95, 105, 1_000);
        String explain = "EXPLAIN QUERY PLAN " + sql("YOUNG", LONG_AGO);
        List<String> plan = database.read(connection -> {
            List<String> details = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(explain)) {
                while (rs.next()) {
                    details.add(rs.getString("detail"));
                }
            }
            return details;
        });

        assertTrue(plan.stream().noneMatch(line -> line.startsWith("SCAN d")),
                "a full scan of daily_bar took over a second on the real database: " + plan);
        assertTrue(plan.stream().anyMatch(line -> line.startsWith("SEARCH d USING")), plan.toString());
    }

    // ---- harness --------------------------------------------------------------------------------------------------

    /** Every weekly candle of the symbol the chart would draw from a date long before the data. */
    private List<Object[]> weeks(String symbol) throws IOException {
        return panel(symbol, LONG_AGO);
    }

    /** The chart's own query with Grafana's variables filled in; its rows (time, OHLC, volume, buy, sell). */
    private List<Object[]> panel(String symbol, LocalDate from) throws IOException {
        String sql = sql(symbol, from);
        return database.read(connection -> {
            List<Object[]> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    Object[] row = new Object[8];
                    for (int c = 0; c < 8; c++) {
                        row[c] = rs.getObject(c + 1);
                    }
                    rows.add(row);
                }
            }
            return rows;
        });
    }

    private static String sql(String symbol, LocalDate from) throws IOException {
        JsonNode panel = StreamSupport.stream(JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD))
                        .path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == 11).findFirst().orElseThrow();
        JsonNode target = panel.path("targets").get(0);
        String query = target.path("queryText").asText();
        assertEquals(query, target.path("rawQueryText").asText(), "the plugin runs queryText; the editor shows the raw");
        query = query.replace("$symbol", symbol)
                .replace("$__from", String.valueOf(from.atStartOfDay(ZoneId.of("Asia/Kolkata"))
                        .toInstant().toEpochMilli()));
        assertTrue(!query.contains("$"), "an unfilled variable would make this prove nothing: " + query);
        return query;
    }

    /** 09:15 IST on the date, as epoch seconds. */
    private static long epoch(LocalDate date) {
        return date.toEpochDay() * 86_400L + 3 * 3_600L + 45 * 60L;
    }

    private void bar(long instrument, LocalDate date, double open, double high, double low, double close,
                     long volume) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO daily_bar (instrument_id, session_date, ts_epoch, open, high, low, close, volume)
                    VALUES (?,?,?,?,?,?,?,?)""")) {
                insert.setLong(1, instrument);
                insert.setString(2, date.toString());
                insert.setLong(3, epoch(date));
                insert.setDouble(4, open);
                insert.setDouble(5, high);
                insert.setDouble(6, low);
                insert.setDouble(7, close);
                insert.setLong(8, volume);
                insert.executeUpdate();
            }
        });
    }

    private void session(long instrument, LocalDate date, double open, double high, double low, double close,
                         long volume) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO trading_day (instrument_id, session_date, interval_minutes, open, high, low, close,
                                             volume, candle_count, first_candle_ts, last_candle_ts, source, ingested_at)
                    VALUES (?,?,1,?,?,?,?,?,375,?,?,'test',0)""")) {
                insert.setLong(1, instrument);
                insert.setString(2, date.toString());
                insert.setDouble(3, open);
                insert.setDouble(4, high);
                insert.setDouble(5, low);
                insert.setDouble(6, close);
                insert.setLong(7, volume);
                insert.setLong(8, epoch(date));
                insert.setLong(9, epoch(date) + 22_500);
                insert.executeUpdate();
            }
        });
    }

    private void trade(long instrument, String side, LocalDate date, double price) {
        long id = ++fills;
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO trade (instrument_id, broker_trade_id, order_id, session_date, executed_ts, side,
                                       quantity, price, product, charges_source, source, imported_at)
                    VALUES (?,?,?,?,?,?,10,?,'MIS','MODELLED','BROKER',0)""")) {
                insert.setLong(1, instrument);
                insert.setString(2, "t" + id);
                insert.setString(3, "o" + id);
                insert.setString(4, date.toString());
                insert.setLong(5, epoch(date) + 600);
                insert.setString(6, side);
                insert.setDouble(7, price);
                insert.executeUpdate();
            }
        });
    }
}
