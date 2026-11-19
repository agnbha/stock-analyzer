package com.stockanalyzer.canslim;

import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteCompanyProfileRepository;
import com.stockanalyzer.store.jdbc.SqliteConcallSummaryRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading a concall summary on request: which call, and never twice by accident. The company is invented. */
class ConcallSummaryServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC);
    private static final String SUMMARY = "<article><h1>Takeaways</h1><ul><li>Point one</li><li>Point two</li></ul>"
            + "</article><p>Summaries viewed: 4 / 10 in the last 30 days.</p>";

    @TempDir
    Path directory;

    private Database database;
    private final List<String> read = new ArrayList<>();

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static CompanyProfile page(CompanyProfile.Concall... calls) {
        return new CompanyProfile("A company.", "Sector", 1000.0, 100.0, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, List.of(), List.of(), List.of(), List.of(calls));
    }

    private static CompanyProfile.Concall call(String month, String summary) {
        return new CompanyProfile.Concall(YearMonth.parse(month), null, null, null, summary);
    }

    private ConcallSummaryService service(CompanyProfile profile) {
        return new ConcallSummaryService(symbol -> Optional.of(profile), path -> {
            read.add(path);
            return SUMMARY;
        }, new SqliteCompanyProfileRepository(database), new SqliteConcallSummaryRepository(database),
                new SqliteInstrumentRepository(database), CLOCK, "NSE", "CASH");
    }

    @Test
    @DisplayName("the latest call screener.in has a summary of, stored line by line, with the allowance it reported")
    void latestCallWithASummary() {
        CompanyProfile profile = page(call("2026-10", null), call("2026-07", "/concalls/summary/7/"),
                call("2026-04", "/concalls/summary/4/"));

        ConcallSummaryService.Result result = service(profile).read("EXAMPLE", null, false);

        assertEquals(List.of("/concalls/summary/7/"), read, "October's call has no summary yet");
        assertEquals(YearMonth.of(2026, 7), result.call());
        assertEquals(3, result.lines());
        assertEquals(4, result.viewed());
        assertEquals(10, result.allowance());
        assertEquals("3", scalar("SELECT COUNT(*) FROM company_concall_summary WHERE period = '2026-07'"));
        assertEquals("3", scalar("SELECT COUNT(*) FROM company_concall"), "the page read on the way is stored too");
    }

    @Test
    @DisplayName("a summary already held is not read again, so none of the allowance goes on it, unless asked")
    void heldSummariesAreNotReadAgain() {
        CompanyProfile profile = page(call("2026-07", "/concalls/summary/7/"));
        service(profile).read("EXAMPLE", null, false);

        ConcallSummaryService.Result again = service(profile).read("EXAMPLE", null, false);
        assertEquals(1, read.size());
        assertEquals(CLOCK.instant().getEpochSecond(), again.heldSince());
        assertNull(again.viewed());

        service(profile).read("EXAMPLE", null, true);
        assertEquals(2, read.size(), "--again");
        assertEquals("3", scalar("SELECT COUNT(*) FROM company_concall_summary"), "replaced, not added to");
    }

    @Test
    @DisplayName("another listed call by its month; a month without a summary is an error, and nothing is read")
    void aChosenCall() {
        CompanyProfile profile = page(call("2026-07", "/concalls/summary/7/"), call("2026-04", "/concalls/summary/4/"));

        assertEquals(YearMonth.of(2026, 4), service(profile).read("EXAMPLE", YearMonth.of(2026, 4), false).call());
        ScreenerException none = assertThrows(ScreenerException.class,
                () -> service(profile).read("EXAMPLE", YearMonth.of(2026, 1), false));
        assertTrue(none.getMessage().contains("2026-01"), none.getMessage());
        assertEquals(List.of("/concalls/summary/4/"), read);
    }

    private String scalar(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }
}
