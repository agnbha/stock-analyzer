package com.stockanalyzer.store;

import com.stockanalyzer.model.AccountSnapshot;
import com.stockanalyzer.model.PeriodType;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.jdbc.SqliteAccountBalanceRepository;
import com.stockanalyzer.store.jdbc.SqliteAccountProfileRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.store.jdbc.SqlitePnlPeriodRepository;
import com.stockanalyzer.store.jdbc.SqliteRealizedLotRepository;
import com.stockanalyzer.store.jdbc.SqliteTradeRepository;
import com.stockanalyzer.store.jdbc.SqliteTradingDayRepository;
import com.stockanalyzer.trade.ChargeRates;
import com.stockanalyzer.trade.FifoLotMatcher;
import com.stockanalyzer.trade.GrowwChargeModel;
import com.stockanalyzer.trade.PeriodAggregator;
import com.stockanalyzer.trade.PeriodBounds;
import com.stockanalyzer.trade.TradeJournalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two Groww accounts in one database: each sees only its own journal, balance and holdings, and the stock data they
 * share is not touched by either. The amounts are invented.
 */
class AccountProfilesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 14);

    @TempDir
    Path directory;

    private Database database;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("one account's buy is never matched with another account's sell")
    void journalsStayApart() {
        TradeJournalService first = journal("first");
        TradeJournalService second = journal("second");
        first.importTrades((from, to) -> List.of(fill("a1", Side.BUY, 10, 100.0, 1)), DAY, DAY);
        second.importTrades((from, to) -> List.of(fill("b1", Side.SELL, 10, 104.0, 2),
                fill("b0", Side.BUY, 10, 101.0, 0)), DAY, DAY);

        var firstMatched = first.rebuild();
        var secondMatched = second.rebuild();

        assertEquals(0, firstMatched.lots().size(), "the first account only bought: nothing closed");
        assertEquals(1, firstMatched.openPositions().size());
        assertEquals(1, secondMatched.lots().size(), "the second sold what it bought itself");
        assertEquals(101.0, secondMatched.lots().getFirst().buyPrice(), "its own buy, not the first account's");
        assertEquals(1, new SqliteRealizedLotRepository(database, "first").openPositions().size(),
                "rebuilding the second account left the first account's position alone");
        assertEquals(0, new SqliteRealizedLotRepository(database, "second").openPositions().size());
        assertEquals(1, new SqliteTradeRepository(database, "first").findRange(DAY, DAY).size());
        assertEquals(2, new SqliteTradeRepository(database, "second").findAllOrdered().size());
    }

    @Test
    @DisplayName("each account's balance and holdings are its own, on the same day")
    void balancesStayApart() {
        AccountBalanceRepository first = new SqliteAccountBalanceRepository(database, "first");
        AccountBalanceRepository second = new SqliteAccountBalanceRepository(database, "second");
        first.record(DAY, new AccountSnapshot(1000, 0, 1000, 0,
                List.of(new AccountSnapshot.Holding("INFY", null, 2, 1500, 1520.0)), 1L));
        second.record(DAY, new AccountSnapshot(500, 0, 500, 0,
                List.of(new AccountSnapshot.Holding("TCS", null, 1, 3000, 3100.0)), 2L));
        second.record(DAY, 600, null, "manual");

        assertEquals(1000, first.latestOnOrBefore(DAY).orElseThrow().cash());
        assertEquals(600, second.latestOnOrBefore(DAY).orElseThrow().cash(), "replaced only the second's reading");
        assertEquals(List.of("first|INFY", "second|TCS"),
                strings("SELECT profile || '|' || symbol FROM account_holding ORDER BY profile"));
        assertEquals(List.of("first|1"), strings(
                "SELECT profile || '|' || COUNT(*) FROM v_current_holdings WHERE profile = 'first' GROUP BY profile"));
    }

    @Test
    @DisplayName("a period's statement is kept per account")
    void statementsStayApart() {
        TradeJournalService first = journal("first");
        TradeJournalService second = journal("second");
        first.importTrades((from, to) -> List.of(fill("a1", Side.BUY, 10, 100.0, 1),
                fill("a2", Side.SELL, 10, 103.0, 2)), DAY, DAY);
        first.rebuild();
        second.rebuild();

        assertEquals(1, first.pnl(PeriodType.DAY, DAY, null).closedLots());
        assertEquals(0, second.pnl(PeriodType.DAY, DAY, null).closedLots());
        assertEquals(1, new SqlitePnlPeriodRepository(database, "first").find(PeriodType.DAY, DAY, null)
                .orElseThrow().closedLots(), "the second account's empty day did not overwrite the first's");
    }

    @Test
    @DisplayName("rows from before profiles go to the first account, once")
    void unassignedRowsAreAdopted() {
        execute("INSERT INTO account_balance (session_date, cash, source, recorded_at) VALUES ('2026-09-14', 1, 'manual', 0)");
        new SqliteTradeRepository(database, "").importAll(List.of(fill("old", Side.BUY, 1, 10.0, 1)), "NSE", "CASH");
        AccountProfileRepository profiles = new SqliteAccountProfileRepository(database);

        assertEquals(Map.of("trade", 1, "account_balance", 1), profiles.adoptUnassigned("first"));
        assertEquals(Map.of(), profiles.adoptUnassigned("first"), "nothing left to give");
        assertEquals(1, new SqliteTradeRepository(database, "first").findAllOrdered().size());
    }

    @Test
    @DisplayName("the configured accounts are listed in order; one dropped from the configuration goes last")
    void registeredInOrder() {
        AccountProfileRepository profiles = new SqliteAccountProfileRepository(database);
        profiles.register(List.of("first", "second", "third"));
        profiles.register(List.of("third", "first"));

        assertEquals(List.of("third", "first", "second"),
                strings("SELECT name FROM account_profile ORDER BY position, name"),
                "the order the dashboards' Account picker lists them in");
        var summaries = profiles.summaries();
        assertEquals("second", summaries.get(2).name());
        assertEquals(null, summaries.get(2).position(), "no longer configured");
        assertEquals(0, summaries.getFirst().position());
    }

    @Test
    @DisplayName("renaming moves every row of an account, and refuses a name that already has rows")
    void rename() {
        journal("old").importTrades((from, to) -> List.of(fill("a1", Side.BUY, 10, 100.0, 1)), DAY, DAY);
        journal("old").rebuild();
        journal("taken").importTrades((from, to) -> List.of(fill("b1", Side.BUY, 1, 100.0, 2)), DAY, DAY);
        AccountProfileRepository profiles = new SqliteAccountProfileRepository(database);
        profiles.register(List.of("old"));

        assertThrows(IllegalArgumentException.class, () -> profiles.rename("old", "taken"),
                "two accounts' journals would be merged");
        assertEquals(Map.of("trade", 1, "open_position", 1), profiles.rename("old", "new"));
        assertEquals(1, new SqliteRealizedLotRepository(database, "new").openPositions().size());
        assertEquals(List.of("new"), strings("SELECT name FROM account_profile"));
    }

    @Test
    @DisplayName("the stock data is the same for every account")
    void stockDataIsShared() {
        journal("first").importTrades((from, to) -> List.of(fill("a1", Side.BUY, 10, 100.0, 1)), DAY, DAY);
        journal("second").importTrades((from, to) -> List.of(fill("b1", Side.BUY, 10, 100.0, 2)), DAY, DAY);

        assertEquals(List.of("1"), strings("SELECT COUNT(*) FROM instrument WHERE symbol = 'INFY'"),
                "one instrument, whichever account traded it");
        for (String table : List.of("instrument", "candle", "trading_day", "daily_bar", "gain_opportunity")) {
            assertTrue(strings("SELECT name FROM pragma_table_info('" + table + "') WHERE name = 'profile'").isEmpty(),
                    table + " has no profile");
        }
    }

    private TradeJournalService journal(String profile) {
        return new TradeJournalService(new SqliteTradeRepository(database, profile),
                new SqliteRealizedLotRepository(database, profile), new SqlitePnlPeriodRepository(database, profile),
                new SqliteInstrumentRepository(database), new SqliteTradingDayRepository(database),
                new FifoLotMatcher(), new GrowwChargeModel(ChargeRates.defaults()), new PeriodAggregator(),
                new PeriodBounds(4), false, "NSE", "CASH");
    }

    /** {@code minute} minutes after 09:15 IST on {@link #DAY}. */
    private static Trade fill(String id, Side side, int quantity, double price, int minute) {
        long at = DAY.toEpochDay() * 86_400L + 3 * 3_600L + 45 * 60L + minute * 60L;
        return new Trade(0, "INFY", id, "o-" + id, DAY, at, side, quantity, price, Product.MIS, 0, null,
                Trade.ChargesSource.MODELLED, Trade.TradeSource.BROKER, null);
    }

    private void execute(String sql) {
        database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });
    }

    private List<String> strings(String sql) {
        return database.read(connection -> {
            List<String> rows = new java.util.ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        });
    }
}
