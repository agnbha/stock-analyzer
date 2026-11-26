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
 * The "Candle size" box on Candles and Calls: the minute chart draws 1, 2, 5 or 10 minute candles, cut on the clock
 * from the 09:15 open, never across a session's end. Checked by running the panel's own query over seeded candles.
 *
 * <p>Minute {@code i} of a session is {@code i} minutes after 09:15. It opens at {@code 1000 + i}, closes at
 * {@code 1000 + i}, has a high of one more and a low of one less, and a volume of 1,000, so a drawn candle shows
 * exactly which minutes it holds.
 */
class MinuteChartCandleSizeTest {

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
    @DisplayName("1M is a candle per minute")
    void oneMinute() throws Exception {
        candles(LATER, 250);

        List<Candle> drawn = draw("1", LATER);

        assertEquals(250, drawn.size());
        assertEquals(1000, drawn.get(0).open());
        assertEquals(1000, drawn.get(0).close(), "one minute each");
        assertEquals(1249, drawn.get(249).close());
    }

    @Test
    @DisplayName("5M: 250 minutes are 50 candles of five, opening where the first opened and closing where the last closed")
    void fiveMinutes() throws Exception {
        candles(LATER, 250);

        List<Candle> drawn = draw("5", LATER);

        assertEquals(50, drawn.size());
        Candle first = drawn.get(0);
        assertEquals(0, first.minute(), "drawn at 09:15, where it starts");
        assertEquals(1000, first.open(), "opens where its first minute opened");
        assertEquals(1004, first.close(), "closes where its fifth minute closed");
        assertEquals(1005, first.high(), "the highest high of the five");
        assertEquals(999, first.low(), "the lowest low of the five");
        assertEquals(5000, first.volume(), "the five volumes added");
        assertEquals(5, drawn.get(1).minute(), "the next starts five minutes on, at 09:20");
        assertEquals(1249, drawn.get(49).close(), "the last candle ends on the last minute: nothing is left off");
    }

    @Test
    @DisplayName("2M and 10M cut the same minutes into pairs and tens")
    void twoAndTenMinutes() throws Exception {
        candles(LATER, 250);

        List<Candle> pairs = draw("2", LATER);
        assertEquals(125, pairs.size());
        assertEquals(1001, pairs.get(0).close());
        assertEquals(2000, pairs.get(0).volume());

        List<Candle> tens = draw("10", LATER);
        assertEquals(25, tens.size());
        assertEquals(1009, tens.get(0).close());
        assertEquals(10_000, tens.get(0).volume());
        assertEquals(1010, tens.get(0).high(), "the highest high of the ten");
        assertEquals(999, tens.get(0).low());
        assertEquals(10, tens.get(1).minute());
    }

    @Test
    @DisplayName("candles are cut from the 09:15 open, so a session that starts late has a short first candle")
    void cutOnTheClock() throws Exception {
        span(LATER, 2, 4);      // 09:17 to 09:19
        span(LATER, 5, 7);      // 09:20 to 09:22

        List<Candle> five = draw("5", LATER);
        assertEquals(2, five.size());
        assertEquals(0, five.get(0).minute(), "the 09:15 candle, though trading began at 09:17");
        assertEquals(1002, five.get(0).open());
        assertEquals(1004, five.get(0).close());
        assertEquals(3000, five.get(0).volume(), "three minutes' volume: not stretched to five");
        assertEquals(5, five.get(1).minute());

        List<Candle> ten = draw("10", LATER);
        assertEquals(1, ten.size(), "09:17 to 09:22 all fall in 09:15-09:24");
        assertEquals(6000, ten.get(0).volume());
    }

    @Test
    @DisplayName("no candle spans two sessions")
    void neverCrossesASession() throws Exception {
        candles(EARLIER, 151);
        candles(LATER, 250);

        List<Candle> drawn = draw("5", EARLIER, LATER);

        assertEquals(31 + 50, drawn.size());
        assertEquals(31, drawn.stream().filter(c -> c.session().equals(EARLIER)).count());
        assertEquals(50, drawn.stream().filter(c -> c.session().equals(LATER)).count());
        for (Candle candle : drawn) {
            int minutes = (int) (candle.close() - candle.open()) + 1;
            assertTrue(minutes >= 1 && minutes <= 5, "five minutes at most, of one session: " + candle);
        }
        assertEquals(1150, drawn.get(30).close(), "the earlier session's last candle holds its last minute, alone");
        assertEquals(1000, drawn.get(31).open(), "and the later one starts afresh");
    }

    @Test
    @DisplayName("longer candles lose nothing: the volume is the same and the range is the same")
    void totalsAreKept() throws Exception {
        candles(EARLIER, 151);
        candles(LATER, 250);

        List<Candle> minutes = draw("1", EARLIER, LATER);
        List<Candle> tens = draw("10", EARLIER, LATER);

        assertTrue(tens.size() < minutes.size());
        assertEquals(sum(minutes), sum(tens), "volume");
        assertEquals(minutes.stream().mapToDouble(Candle::high).max().orElseThrow(),
                tens.stream().mapToDouble(Candle::high).max().orElseThrow());
        assertEquals(minutes.stream().mapToDouble(Candle::low).min().orElseThrow(),
                tens.stream().mapToDouble(Candle::low).min().orElseThrow());
    }

    @Test
    @DisplayName("VWAP is read at the end of each candle, as it stands then")
    void vwapIsReadAtTheEndOfTheCandle() throws Exception {
        candles(LATER, 250);

        List<Candle> minutes = draw("1", LATER);
        List<Candle> fives = draw("5", LATER);

        for (int i = 0; i < fives.size(); i++) {
            assertEquals(minutes.get(i * 5 + 4).vwap(), fives.get(i).vwap(), "VWAP of candle " + i);
        }
    }

    @Test
    @DisplayName("a buy or a sell lands on the candle that holds its minute, at its price; fills in one candle are averaged")
    void fillsLandOnTheirCandle() throws Exception {
        candles(LATER, 250);
        fill("b1", Side.BUY, minuteOf(LATER, 7) + 20, 1007.5);
        fill("b2", Side.BUY, minuteOf(LATER, 8) + 30, 1008.5);
        fill("s1", Side.SELL, minuteOf(LATER, 40), 1040.0);

        List<Candle> perMinute = draw("1", LATER);
        assertEquals(1007.5, perMinute.get(7).buy(), "on its own minute's candle");
        assertEquals(1008.5, perMinute.get(8).buy());
        assertEquals(2, perMinute.stream().filter(c -> c.buy() != null).count(), "and nowhere else");
        assertEquals(1040.0, perMinute.get(40).sell());

        List<Candle> fives = draw("5", LATER);
        assertNull(fives.get(0).buy());
        assertEquals(1008.0, fives.get(1).buy(), "minutes 5-9 hold both buys: their average");
        assertEquals(1, fives.stream().filter(c -> c.buy() != null).count());
        assertEquals(1040.0, fives.get(8).sell(), "minute 40 is in the ninth candle, minutes 40-44");
        assertEquals(1, fives.stream().filter(c -> c.sell() != null).count());
    }

    @Test
    @DisplayName("a detector window's entry and exit land on the candles that hold their minutes")
    void windowsLandOnTheirCandle() throws Exception {
        candles(LATER, 250);
        window(LATER, 12, 33);

        List<Candle> fives = draw("5", LATER);

        assertEquals(1012.0, fives.get(2).entry(), "minute 12 is in minutes 10-14");
        assertEquals(1033.0, fives.get(6).exit(), "minute 33 is in minutes 30-34");
        assertEquals(1, fives.stream().filter(c -> c.entry() != null).count());
        assertEquals(1, fives.stream().filter(c -> c.exit() != null).count());
    }

    @Test
    @DisplayName("a fill in a minute with no candle of its own still lands on the candle that spans it")
    void aFillInAQuietMinuteLands() throws Exception {
        span(LATER, 0, 8);
        span(LATER, 10, 14);
        fill("b1", Side.BUY, minuteOf(LATER, 9) + 10, 1009.0);      // nothing traded in minute 9

        List<Candle> fives = draw("5", LATER);

        assertEquals(1009.0, fives.get(1).buy(), "minute 9 is in 09:20-09:24, though that candle's last minute is 8");
    }

    @Test
    @DisplayName("the close after the closing auction is a candle of its own, at every size")
    void theClosingPrintStandsAlone() throws Exception {
        // An F&O stock since August 2026: minutes 0-360 trade (9:15 to 15:15), nothing until one print at minute 373.
        span(LATER, 0, 360);
        span(LATER, 373, 373);

        List<Candle> ones = draw("1", LATER);
        assertEquals(362, ones.size());
        assertEquals(373, ones.getLast().minute());

        List<Candle> fives = draw("5", LATER);
        assertEquals(74, fives.size(), "73 candles for minutes 0-360, and the close");
        Candle close = fives.getLast();
        assertEquals(370, close.minute(), "in the 15:25-15:29 candle, alone");
        assertEquals(1373, close.open());
        assertEquals(1373, close.close());
        assertEquals(1000, close.volume(), "the print's own volume");
        assertEquals(1360, fives.get(72).open(), "minute 360 stands alone before it: nothing traded in 361-369");
        assertEquals(1360, fives.get(72).close());

        List<Candle> tens = draw("10", LATER);
        assertEquals(38, tens.size());
        assertEquals(1000, tens.getLast().volume());
        assertEquals(1373, tens.getLast().open());
        assertEquals(1360, tens.get(36).close(), "minute 360 alone in 15:15-15:24");

        assertEquals(1000, draw("2", LATER).getLast().volume());
    }

    @Test
    @DisplayName("a quiet stretch leaves a gap, not a longer candle")
    void aPauseLeavesAGap() throws Exception {
        span(EARLIER, 0, 8);
        span(EARLIER, 11, 20);

        List<Candle> fives = draw("5", EARLIER);

        assertEquals(List.of(0, 5, 10, 15, 20), fives.stream().map(Candle::minute).toList());
        assertEquals(1005, fives.get(1).open());
        assertEquals(1008, fives.get(1).close(), "minutes 5-8: 9 and 10 did not trade");
        assertEquals(1011, fives.get(2).open(), "minute 11 starts the next candle, as the clock does");
        assertEquals(1014, fives.get(2).close());
    }

    @Test
    @DisplayName("anything but 1, 2, 5 or 10 is 5M, the box's default, never an error, and nothing typed can touch the data")
    void badInputFallsBackToTheDefault() throws Exception {
        candles(LATER, 50);

        for (String typed : new String[]{"", "abc", "0", "-5", "7", "  ", "20", "5; DROP TABLE live_candle", "1; DROP TABLE live_candle"}) {
            assertEquals(10, draw(typed, LATER).size(), "typed: '" + typed + "': five-minute candles");
        }
        assertEquals(50, draw("1", LATER).size(), "1 is still one minute");
        assertEquals(50, count("live_candle"));
    }

    @Test
    @DisplayName("the page has the Candle size dropdown, defaulting to 5M, and no Max candles box")
    void dashboardHasTheBox() throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        List<JsonNode> variables = new ArrayList<>();
        root.path("templating").path("list").forEach(variables::add);
        JsonNode box = variables.stream().filter(v -> v.path("name").asText().equals("candle_size")).findFirst().orElseThrow();

        assertEquals("Candle size", box.path("label").asText());
        assertEquals("custom", box.path("type").asText(), "a dropdown");
        assertEquals(false, box.path("multi").asBoolean());
        assertEquals(false, box.path("includeAll").asBoolean());
        List<String> values = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        box.path("options").forEach(o -> {
            values.add(o.path("value").asText());
            texts.add(o.path("text").asText());
        });
        assertEquals(List.of("1", "2", "5", "10"), values);
        assertEquals(List.of("1M", "2M", "5M", "10M"), texts);
        assertEquals("5", box.path("current").path("value").asText(), "the default");
        assertEquals("5M", box.path("current").path("text").asText());
        List<String> selected = new ArrayList<>();
        box.path("options").forEach(o -> {
            if (o.path("selected").asBoolean()) {
                selected.add(o.path("value").asText());
            }
        });
        assertEquals(List.of("5"), selected, "only 5M is selected to begin with");
        assertTrue(!box.path("description").asText().isBlank(), "the box says what it does when hovered");

        assertEquals(0, variables.stream().filter(v -> v.path("name").asText().equals("candles")).count(),
                "Max candles is gone, so an address carrying var-candles=... is ignored");
        assertTrue(panel().path("title").asText().contains("${candle_size:text}"), "the title states the size");
        assertTrue(!panel().path("targets").get(0).path("queryText").asText().contains("$candles"));
    }

    private static JsonNode panel() throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        return StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == 20).findFirst().orElseThrow();
    }

    /** {@code minute} is the index of the minute the candle starts at, as {@link #minuteOf} counts them. */
    private record Candle(String session, int minute, double open, double close, double high, double low,
                          double volume, Double vwap, Double entry, Double exit, Double buy, Double sell) {
    }

    private static double sum(List<Candle> candles) {
        return candles.stream().mapToDouble(Candle::volume).sum();
    }

    /** The panel's own query, with Grafana's variables filled in; the box is given as typed. */
    private List<Candle> draw(String size, String... sessions) throws Exception {
        String sessionList = "'" + String.join("','", sessions) + "'";
        String sql = panel().path("targets").get(0).path("queryText").asText()
                .replace("${session:sqlstring}", sessionList)
                .replace("${profile:sqlstring}", "'" + AccountProfileRepository.DEFAULT_PROFILE + "'")
                .replace("$symbol", "TEST")
                .replace("$candle_size", size.replace("'", "''"))
                .replace("${levels:csv}", "vwap")
                .replace("$detector", DETECTOR);
        assertTrue(!sql.contains("$"), "an unfilled variable would make this prove nothing: " + sql);
        return database.read(connection -> {
            List<Candle> drawn = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    long time = rs.getLong("time");
                    LocalDate session = LocalDate.ofEpochDay(time / 86_400L);
                    drawn.add(new Candle(session.toString(), (int) ((time - minuteOf(session.toString(), 0)) / 60),
                            rs.getDouble("open"), rs.getDouble("close"), rs.getDouble("high"), rs.getDouble("low"),
                            rs.getDouble("volume"), nullable(rs, "VWAP"),
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

    /** Minute {@code index} of the session: 09:15 IST is 03:45 UTC, so candles cut on the clock line up with the index. */
    private static long minuteOf(String date, int index) {
        return LocalDate.parse(date).toEpochDay() * 86_400L + 3 * 3_600L + 45 * 60L + index * 60L;
    }

    /** {@code count} one-minute candles for the session, within its own UTC day so sessions can be told apart. */
    private void candles(String date, int count) {
        span(date, 0, count - 1);
    }

    /** One-minute candles for minutes {@code first} to {@code last} of the session, priced as {@link #candles} does. */
    private void span(String date, int first, int last) {
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, open, high,
                                             low, close, volume, provisional, updated_at)
                    VALUES (?,1,?,?,?,?,?,?,1000,0,0)""")) {
                for (int i = first; i <= last; i++) {
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
