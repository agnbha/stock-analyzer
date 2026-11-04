package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Max candles" box on Candles and Calls: the minute chart draws at most that many candles, 200 when the box is
 * left alone, by combining minutes into longer candles across everything selected. Checked by running the panel's own
 * query over seeded candles.
 *
 * <p>Candle {@code i} of a session opens at {@code 1000 + i}, closes at {@code 1000 + i}, has a high of one more and a
 * low of one less, and a volume of 1,000, so a drawn candle shows exactly which minutes it holds.
 */
class MinuteChartCandleCapTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");
    private static final String EARLIER = "2026-08-26";
    private static final String LATER = "2026-08-27";
    private static final String DETECTOR = "topk-nonoverlap/highlow/v1";

    @TempDir
    Path directory;

    private Database database;
    private long instrument;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instrument = new SqliteInstrumentRepository(database).findOrCreate("TEST", "NSE", "CASH");
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("left alone, 250 minutes are drawn as 125 two-minute candles: under the 200 cap, and no fewer than needed")
    void defaultsTo200() throws Exception {
        candles(LATER, 250);

        List<Candle> drawn = draw("200", LATER);

        assertEquals(125, drawn.size());
        Candle first = drawn.get(0);
        assertEquals(1000, first.open(), "opens where its first minute opened");
        assertEquals(1001, first.close(), "closes where its second minute closed");
        assertEquals(1002, first.high(), "the highest high of the two");
        assertEquals(999, first.low(), "the lowest low of the two");
        assertEquals(2000, first.volume(), "the two volumes added");
        assertEquals(1249, drawn.get(124).close(), "the last candle ends on the last minute: nothing is left off");
    }

    @Test
    @DisplayName("with room to spare it is a candle per minute, however large the number")
    void roomToSpare() throws Exception {
        candles(LATER, 250);

        for (String typed : new String[]{"250", "375", "1000", "100000"}) {
            List<Candle> drawn = draw(typed, LATER);
            assertEquals(250, drawn.size(), "typed: " + typed);
            assertEquals(1000, drawn.get(0).open());
            assertEquals(1000, drawn.get(0).close(), "one minute each");
            assertEquals(1249, drawn.get(249).close());
        }
    }

    @Test
    @DisplayName("a smaller number makes longer candles that still cover the whole selection")
    void configurable() throws Exception {
        candles(LATER, 250);

        List<Candle> drawn = draw("50", LATER);

        assertEquals(50, drawn.size());
        assertEquals(1000, drawn.get(0).open());
        assertEquals(1004, drawn.get(0).close(), "five minutes in each");
        assertEquals(1249, drawn.get(49).close());
        assertEquals(1, draw("1", LATER).size(), "a number too small for one candle per session gives one per session");
    }

    @Test
    @DisplayName("no candle spans two sessions, and the total never goes over the number typed")
    void neverCrossesASessionNorExceedsTheCap() throws Exception {
        candles(EARLIER, 151);
        candles(LATER, 250);

        List<Candle> drawn = draw("200", EARLIER, LATER);

        // 2-minute candles would be 76 + 125 = 201, one too many, so the width steps up to 3: 51 + 84.
        assertEquals(135, drawn.size());
        assertEquals(51, drawn.stream().filter(c -> c.session().equals(EARLIER)).count());
        assertEquals(84, drawn.stream().filter(c -> c.session().equals(LATER)).count());
        for (Candle candle : drawn) {
            int minutes = (int) (candle.close() - candle.open()) + 1;
            assertTrue(minutes >= 1 && minutes <= 3,
                    "a candle of 1-3 minutes of one session, not a span into the next: " + candle);
        }
    }

    @Test
    @DisplayName("combining loses nothing: the volume is the same and the range is the same")
    void totalsAreKept() throws Exception {
        candles(EARLIER, 151);
        candles(LATER, 250);

        List<Candle> minutes = draw("100000", EARLIER, LATER);
        List<Candle> combined = draw("40", EARLIER, LATER);

        assertTrue(combined.size() <= 40 && combined.size() < minutes.size());
        assertEquals(sum(minutes), sum(combined), "volume");
        assertEquals(minutes.stream().mapToDouble(Candle::high).max().orElseThrow(),
                combined.stream().mapToDouble(Candle::high).max().orElseThrow());
        assertEquals(minutes.stream().mapToDouble(Candle::low).min().orElseThrow(),
                combined.stream().mapToDouble(Candle::low).min().orElseThrow());
    }

    @Test
    @DisplayName("VWAP and the moving averages are read at the end of each candle, as they stand then")
    void averagesAreReadAtTheEndOfTheCandle() throws Exception {
        candles(LATER, 250);

        List<Candle> minutes = draw("100000", LATER);
        List<Candle> combined = draw("50", LATER);

        for (int i = 0; i < combined.size(); i++) {
            Candle atItsLastMinute = minutes.get(i * 5 + 4);
            assertEquals(atItsLastMinute.vwap(), combined.get(i).vwap(), "VWAP of candle " + i);
            assertEquals(atItsLastMinute.sma60(), combined.get(i).sma60(), "SMA 60m of candle " + i);
        }
    }

    @Test
    @DisplayName("blank, text, zero or a negative number is 200, never every minute and never an error")
    void badInputFallsBackToTheDefault() throws Exception {
        candles(LATER, 250);

        for (String typed : new String[]{"", "abc", "0", "-5", "  "}) {
            assertEquals(125, draw(typed, LATER).size(), "typed: '" + typed + "'");
        }
        // Text is quoted into the query as a string, so it cannot add SQL: SQLite reads the leading digits and no more
        // (20 candles for 250 minutes needs 13-minute candles: 20 of them).
        assertEquals(20, draw("20; DROP TABLE live_candle", LATER).size());
        assertEquals(250, count("live_candle"), "nothing typed in the box can touch the data");
    }

    @Test
    @DisplayName("a buy or a sell lands on the candle that holds its minute, at its price; fills in one candle are averaged")
    void fillsLandOnTheirCandle() throws Exception {
        candles(LATER, 250);
        fill("b1", Side.BUY, minuteOf(LATER, 7) + 20, 1007.5);
        fill("b2", Side.BUY, minuteOf(LATER, 8) + 30, 1008.5);
        fill("s1", Side.SELL, minuteOf(LATER, 40), 1040.0);

        List<Candle> perMinute = draw("100000", LATER);
        assertEquals(1007.5, perMinute.get(7).buy(), "on its own minute's candle when there is one per minute");
        assertEquals(1008.5, perMinute.get(8).buy());
        assertEquals(2, perMinute.stream().filter(c -> c.buy() != null).count(), "and nowhere else");
        assertEquals(1040.0, perMinute.get(40).sell());

        List<Candle> combined = draw("50", LATER);
        assertNull(combined.get(0).buy());
        assertEquals(1008.0, combined.get(1).buy(), "minutes 5-9 hold both buys: their average");
        assertEquals(1, combined.stream().filter(c -> c.buy() != null).count());
        assertEquals(1040.0, combined.get(8).sell(), "minute 40 is in the ninth candle, minutes 40-44");
        assertEquals(1, combined.stream().filter(c -> c.sell() != null).count());
    }

    @Test
    @DisplayName("a detector window's entry and exit land on the candles that hold their minutes")
    void windowsLandOnTheirCandle() throws Exception {
        candles(LATER, 250);
        window(LATER, 12, 33);

        List<Candle> combined = draw("50", LATER);

        assertEquals(1012.0, combined.get(2).entry(), "minute 12 is in minutes 10-14");
        assertEquals(1033.0, combined.get(6).exit(), "minute 33 is in minutes 30-34");
        assertEquals(1, combined.stream().filter(c -> c.entry() != null).count());
        assertEquals(1, combined.stream().filter(c -> c.exit() != null).count());
    }

    @Test
    @DisplayName("the page has the box, defaulting to 200, and the chart title says how many it draws at most")
    void dashboardHasTheBox() throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        JsonNode box = StreamSupport.stream(root.path("templating").path("list").spliterator(), false)
                .filter(v -> v.path("name").asText().equals("candles")).findFirst().orElseThrow();

        assertEquals("textbox", box.path("type").asText());
        assertEquals("200", box.path("query").asText(), "the default");
        assertEquals("200", box.path("current").path("value").asText());
        assertEquals("Max candles", box.path("label").asText());
        assertTrue(!box.path("description").asText().isBlank(), "the box says what it does when hovered");
        assertTrue(panel().path("title").asText().contains("$candles"), "the title states the number");
    }

    private static JsonNode panel() throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        return StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == 20).findFirst().orElseThrow();
    }

    private record Candle(String session, double open, double close, double high, double low, double volume,
                          Double vwap, Double sma60, Double entry, Double exit, Double buy, Double sell) {
    }

    private static double sum(List<Candle> candles) {
        return candles.stream().mapToDouble(Candle::volume).sum();
    }

    /** The panel's own query, with Grafana's variables filled in as Grafana's "sqlstring" format would. */
    private List<Candle> draw(String typed, String... sessions) throws Exception {
        String sessionList = "'" + String.join("','", sessions) + "'";
        String box = "'" + typed.replace("'", "''") + "'";
        String sql = panel().path("targets").get(0).path("queryText").asText()
                .replace("${session:sqlstring}", sessionList)
                .replace("${candles:sqlstring}", box)
                .replace("$symbol", "TEST")
                .replace("$detector", DETECTOR);
        assertTrue(!sql.contains("$"), "an unfilled variable would make this prove nothing: " + sql);
        return database.read(connection -> {
            List<Candle> drawn = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    long time = rs.getLong("time");
                    drawn.add(new Candle(LocalDate.ofEpochDay(time / 86_400L).toString(),
                            rs.getDouble("open"), rs.getDouble("close"), rs.getDouble("high"), rs.getDouble("low"),
                            rs.getDouble("volume"), nullable(rs, "VWAP"), nullable(rs, "SMA 60m"),
                            nullable(rs, "Window entry"), nullable(rs, "Window exit"),
                            nullable(rs, "Your buy"), nullable(rs, "Your sell")));
                }
            }
            return drawn;
        });
    }

    private static Double nullable(ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private int count(String table) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    private static long minuteOf(String date, int index) {
        return LocalDate.parse(date).toEpochDay() * 86_400L + 4 * 3_600L + index * 60L;
    }

    /** {@code count} one-minute candles for the session, within its own UTC day so sessions can be told apart. */
    private void candles(String date, int count) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, open, high,
                                             low, close, volume, provisional, updated_at)
                    VALUES (?,1,?,?,?,?,?,?,1000,0,0)""")) {
                for (int i = 0; i < count; i++) {
                    double close = 1000 + i;
                    insert.setLong(1, instrument);
                    insert.setLong(2, minuteOf(date, i));
                    insert.setString(3, date);
                    insert.setDouble(4, close);
                    insert.setDouble(5, close + 1);
                    insert.setDouble(6, close - 1);
                    insert.setDouble(7, close);
                    insert.executeUpdate();
                }
            }
        });
    }

    private void fill(String id, Side side, long executedAt, double price) {
        Trade trade = new Trade(0, "TEST", id, "o-" + id, LocalDate.parse(LATER), executedAt, side, 10, price,
                Product.MIS, 0, null, Trade.ChargesSource.MODELLED, Trade.TradeSource.BROKER, null);
        new SqliteTradeRepository(database).importAll(List.of(trade), "NSE", "CASH");
    }

    /** A rank-1 window from minute {@code entry} to minute {@code exit}, priced as those minutes' closes. */
    private void window(String date, int entry, int exit) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_opportunity (instrument_id, session_date, detector_version, rank, entry_ts, exit_ts,
                                                  entry_price, exit_price, gain_pct, duration_minutes, updated_at)
                    VALUES (?,?,?,1,?,?,?,?,1.0,?,0)""")) {
                insert.setLong(1, instrument);
                insert.setString(2, date);
                insert.setString(3, DETECTOR);
                insert.setLong(4, minuteOf(date, entry));
                insert.setLong(5, minuteOf(date, exit));
                insert.setDouble(6, 1000 + entry);
                insert.setDouble(7, 1000 + exit);
                insert.setInt(8, exit - entry);
                insert.executeUpdate();
            }
        });
    }
}
