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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The time ranges of Candles and Calls: the picker's "This week", and the "Yesterday's trading hours" button, whose
 * hidden variables are run over seeded sessions with "today" fixed.
 */
class TradingRangesTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String TODAY = "date('now', '+5 hours', '+30 minutes')";

    // A Monday to the Monday after.
    private static final String MON24 = "2026-08-24";
    private static final String TUE25 = "2026-08-25";
    private static final String WED26 = "2026-08-26";
    private static final String THU27 = "2026-08-27";
    private static final String FRI28 = "2026-08-28";
    private static final String SAT29 = "2026-08-29";
    private static final String SUN30 = "2026-08-30";
    private static final String MON31 = "2026-08-31";

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
    @DisplayName("the picker has This week from Monday 9:15 to now, and the week starts on Monday")
    void thisWeek() throws Exception {
        JsonNode dashboard = dashboard();
        JsonNode entry = null;
        Set<String> names = new HashSet<>();
        for (JsonNode range : dashboard.path("timepicker").path("quick_ranges")) {
            assertTrue(names.add(range.path("display").asText()), "listed twice: " + range.path("display").asText());
            if (range.path("display").asText().startsWith("This week (Monday 9:15")) {
                entry = range;
            }
        }
        assertTrue(entry != null, "This week (Monday 9:15 → now)");
        assertEquals("now/w+9h+15m", entry.path("from").asText(), "the week's start, plus the market's opening");
        assertEquals("now", entry.path("to").asText());
        assertEquals("monday", dashboard.path("weekStart").asText(),
                "now/w follows the viewer's browser otherwise: Sunday for a US locale");
        assertEquals("Asia/Kolkata", dashboard.path("timezone").asText(), "the rounding is done on the market's clock");
        assertEquals("now/d+9h+15m", quickRange("Today's trading hours").path("from").asText(), "still there");
    }

    @Test
    @DisplayName("on a weekday it is the day before, 9:15 to 15:30")
    void aWeekday() throws Exception {
        sessions(MON24, TUE25, WED26, THU27, FRI28);

        assertPrevious(WED26, TUE25);
    }

    @Test
    @DisplayName("on a Monday it is Friday, skipping the weekend")
    void aMonday() throws Exception {
        sessions(THU27, FRI28);

        assertPrevious(MON31, FRI28);
    }

    @Test
    @DisplayName("on a Saturday or a Sunday it is Friday")
    void aWeekend() throws Exception {
        sessions(THU27, FRI28);

        assertPrevious(SAT29, FRI28);
        assertPrevious(SUN30, FRI28);
    }

    @Test
    @DisplayName("a holiday in the middle of the week is skipped, and so is one on the Friday before")
    void aHoliday() throws Exception {
        sessions(MON24, TUE25, THU27);

        assertPrevious(THU27, TUE25, "Wednesday 26 had no session");
        assertPrevious(MON31, THU27, "Friday 28 had none either");
    }

    @Test
    @DisplayName("today is never the previous session, even with minutes stored for it")
    void notToday() throws Exception {
        sessions(THU27);
        liveMinutes(FRI28);

        assertPrevious(FRI28, THU27);
    }

    @Test
    @DisplayName("a day still in staging, not yet settled by the evening batch, counts")
    void aDayInStaging() throws Exception {
        sessions(WED26);
        liveMinutes(THU27);

        assertPrevious(FRI28, THU27);
    }

    @Test
    @DisplayName("with no earlier session there is nothing to jump to")
    void noEarlierSession() throws Exception {
        sessions(FRI28);

        assertNull(variable("prev_day", FRI28));
        assertNull(variable("prev_from", FRI28));
        assertNull(variable("prev_to", FRI28));
    }

    @Test
    @DisplayName("the button carries the symbol, detector and candle count, and sets the session and the range")
    void theButton() throws Exception {
        JsonNode dashboard = dashboard();
        JsonNode button = null;
        for (JsonNode link : dashboard.path("links")) {
            if (link.path("title").asText().equals("Yesterday's trading hours")) {
                button = link;
            }
        }
        assertTrue(button != null, "Yesterday's trading hours");
        assertEquals("link", button.path("type").asText());
        assertFalse(button.path("includeVars").asBoolean(),
                "it would add the current var-session beside ours, and the page would read two sessions");
        assertFalse(button.path("keepTime").asBoolean(), "the range is the point");

        String url = button.path("url").asText();
        assertTrue(url.startsWith("/d/stockanalyzer-candles/candles-and-calls?"), url);
        List<String> parameters = List.of(url.substring(url.indexOf('?') + 1).split("&"));
        assertEquals(1, parameters.stream().filter(p -> p.startsWith("var-session=")).count(), "one session: " + url);
        assertTrue(parameters.contains("var-session=${prev_day}"), url);
        assertTrue(parameters.contains("from=${prev_from}"), url);
        assertTrue(parameters.contains("to=${prev_to}"), url);

        Set<String> defined = new HashSet<>();
        Set<String> shown = new HashSet<>();
        for (JsonNode variable : dashboard.path("templating").path("list")) {
            String name = variable.path("name").asText();
            defined.add(name);
            boolean hidden = variable.path("hide").asInt() == 2;
            if (!hidden) {
                shown.add(name);
            }
            if (!hidden && !name.equals("session")) {
                assertTrue(parameters.contains("${" + name + ":queryparam}"), name + " is carried: " + url);
            }
            if (hidden) {
                assertNotEquals(0, variable.path("refresh").asInt(), name + " is looked up when the page opens or the range changes");
                assertFalse(url.contains("${" + name + ":queryparam}"), name + " is not for the URL as a variable");
            }
        }
        assertEquals(Set.of("symbol", "session", "detector", "candles"), shown,
                "the lookups are hidden: no extra boxes on the page");
        Matcher used = Pattern.compile("\\$\\{(\\w+)(?::\\w+)?}").matcher(url);
        while (used.find()) {
            assertTrue(defined.contains(used.group(1)), "no variable " + used.group(1));
        }
    }

    /** The three hidden variables, for a day, agree on the session and its 09:15 and 15:30 IST. */
    private void assertPrevious(String today, String expected) throws Exception {
        assertPrevious(today, expected, "");
    }

    private void assertPrevious(String today, String expected, String why) throws Exception {
        String note = "today " + today + (why.isEmpty() ? "" : ": " + why);
        assertEquals(expected, variable("prev_day", today), note);
        assertEquals(millis(expected, LocalTime.of(9, 15)), ((Number) variable("prev_from", today)).longValue(), note);
        assertEquals(millis(expected, LocalTime.of(15, 30)), ((Number) variable("prev_to", today)).longValue(), note);
    }

    private static long millis(String date, LocalTime time) {
        return ZonedDateTime.of(LocalDate.parse(date), time, IST).toInstant().toEpochMilli();
    }

    /** A hidden variable's query with "today" fixed; its one value, or null when there is none. */
    private Object variable(String name, String today) throws Exception {
        String sql = null;
        for (JsonNode variable : dashboard().path("templating").path("list")) {
            if (variable.path("name").asText().equals(name)) {
                assertEquals(variable.path("query").asText(), variable.path("definition").asText());
                sql = variable.path("query").asText();
            }
        }
        assertTrue(sql != null, "variable " + name);
        assertTrue(sql.contains(TODAY), name + " reads today by the market's clock");
        String query = sql.replace(TODAY, "'" + today + "'");
        assertFalse(query.contains("$"), "an unfilled variable would make this prove nothing: " + query);
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(query)) {
                assertTrue(rs.next(), name + " gives a row");
                Object value = rs.getObject(1);
                assertFalse(rs.next(), name + " gives one row");
                return value;
            }
        });
    }

    private static JsonNode dashboard() throws IOException {
        return JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
    }

    private static JsonNode quickRange(String display) throws IOException {
        return StreamSupport.stream(dashboard().path("timepicker").path("quick_ranges").spliterator(), false)
                .filter(r -> r.path("display").asText().equals(display)).findFirst().orElseThrow();
    }

    private void sessions(String... dates) {
        for (String date : dates) {
            database.inTransaction(connection -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO trading_day (instrument_id, session_date, interval_minutes, close, candle_count,
                                                 source, ingested_at)
                        VALUES (?,?,1,100,375,'groww',0)""")) {
                    insert.setLong(1, instrument);
                    insert.setString(2, date);
                    insert.executeUpdate();
                }
            });
        }
    }

    /** One minute in staging for a day, as the live monitor writes it before the evening batch settles the day. */
    private void liveMinutes(String date) {
        long open = LocalDate.parse(date).toEpochDay() * 86_400L + 3 * 3_600L + 45 * 60L;
        database.inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO live_candle (instrument_id, interval_minutes, ts_epoch, session_date, open, high, low,
                                             close, volume, provisional, updated_at)
                    VALUES (?,1,?,?,100,101,99,100,500,0,?)""")) {
                insert.setLong(1, instrument);
                insert.setLong(2, open);
                insert.setString(3, date);
                insert.setLong(4, open + 30);
                insert.executeUpdate();
            }
        });
    }
}
