package com.stockanalyzer.intraday;

import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.store.CalendarRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.TradingDayRepository;
import com.stockanalyzer.store.jdbc.SqliteCalendarRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackfillPlannerTest {

    // Monday 24 August 2026 through Sunday 30 August 2026.
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 8, 30);

    @TempDir
    Path directory;

    private Database database;
    private InstrumentRepository instruments;
    private TradingDayRepository tradingDays;
    private CalendarRepository calendarRepository;
    private BackfillPlanner planner;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        instruments = new SqliteInstrumentRepository(database);
        tradingDays = new SqliteTradingDayRepository(database);
        calendarRepository = new SqliteCalendarRepository(database);
        TradingCalendar calendar = new DefaultTradingCalendar(
                holidays(LocalDate.of(2026, 8, 26)), calendarRepository);
        planner = new BackfillPlanner(calendar, instruments, tradingDays);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("weekends and published holidays are never asked for")
    void skipsWeekendsAndHolidays() {
        Map<String, List<LocalDate>> plan = planner.plan(List.of("RELIANCE"), "NSE", "CASH", MONDAY, SUNDAY, 1);

        List<LocalDate> wanted = plan.get("RELIANCE");
        assertEquals(4, wanted.size(), "five weekdays minus one holiday");
        assertFalse(wanted.contains(LocalDate.of(2026, 8, 26)), "published holiday");
        assertFalse(wanted.contains(LocalDate.of(2026, 8, 29)), "Saturday");
        assertFalse(wanted.contains(SUNDAY), "Sunday");
    }

    @Test
    @DisplayName("what is already stored is subtracted, so a rerun asks for nothing")
    void subtractsWhatIsStored() {
        long instrumentId = instruments.findOrCreate("RELIANCE", "NSE", "CASH");
        for (LocalDate date : List.of(MONDAY, MONDAY.plusDays(1))) {
            tradingDays.upsert(instrumentId, summary(date), "groww");
        }

        List<LocalDate> missing = planner.plan(List.of("RELIANCE"), "NSE", "CASH", MONDAY, SUNDAY, 1)
                .get("RELIANCE");

        assertEquals(List.of(LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 28)), missing);
    }

    @Test
    @DisplayName("a symbol with nothing missing drops out of the plan entirely")
    void fullyStoredSymbolsAreOmitted() {
        long instrumentId = instruments.findOrCreate("RELIANCE", "NSE", "CASH");
        for (LocalDate date : List.of(MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(3), MONDAY.plusDays(4))) {
            tradingDays.upsert(instrumentId, summary(date), "groww");
        }

        Map<String, List<LocalDate>> plan =
                planner.plan(List.of("RELIANCE", "TCS"), "NSE", "CASH", MONDAY, SUNDAY, 1);

        assertFalse(plan.containsKey("RELIANCE"));
        assertTrue(plan.containsKey("TCS"), "a symbol never fetched is entirely missing");
    }

    @Test
    @DisplayName("a day inferred to be a holiday is not asked for again")
    void inferredHolidaysAreSkipped() {
        calendarRepository.markNonTrading(LocalDate.of(2026, 8, 27), "holiday-inferred");

        List<LocalDate> missing = planner.plan(List.of("RELIANCE"), "NSE", "CASH", MONDAY, SUNDAY, 1)
                .get("RELIANCE");

        assertFalse(missing.contains(LocalDate.of(2026, 8, 27)));
    }

    @Test
    @DisplayName("a gap left by an earlier failure is picked up next run with no retry bookkeeping")
    void selfHealsAfterAFailure() {
        long instrumentId = instruments.findOrCreate("RELIANCE", "NSE", "CASH");
        // Tuesday failed three days ago; Monday and Thursday succeeded.
        tradingDays.upsert(instrumentId, summary(MONDAY), "groww");
        tradingDays.upsert(instrumentId, summary(MONDAY.plusDays(3)), "groww");

        List<LocalDate> missing = planner.plan(List.of("RELIANCE"), "NSE", "CASH", MONDAY, SUNDAY, 1)
                .get("RELIANCE");

        assertTrue(missing.contains(LocalDate.of(2026, 8, 25)), "the failed Tuesday is simply still missing");
    }

    private static DailyGainSummary summary(LocalDate date) {
        return new DailyGainSummary("RELIANCE", date, 1, 100, 110, 95, 105, 1000, null, 375,
                1756000000L, 1756022500L, List.of());
    }

    private static HolidaySource holidays(LocalDate... dates) {
        Set<LocalDate> set = Set.of(dates);
        return new HolidaySource() {
            @Override
            public Set<LocalDate> holidays() {
                return set;
            }

            @Override
            public Optional<LocalDate> coveredUntil() {
                return set.stream().max(LocalDate::compareTo);
            }
        };
    }
}
