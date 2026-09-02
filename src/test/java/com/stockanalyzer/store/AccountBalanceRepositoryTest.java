package com.stockanalyzer.store;

import com.stockanalyzer.model.AccountSnapshot;
import com.stockanalyzer.store.jdbc.SqliteAccountBalanceRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The account value comes from the broker, so what gets stored has to survive the round trip. */
class AccountBalanceRepositoryTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);

    private Database database;
    private AccountBalanceRepository repository;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        database = Database.open("jdbc:sqlite:" + dir.resolve("test.db"), 5000);
        new SchemaMigrator(database).migrate();
        repository = new SqliteAccountBalanceRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static AccountSnapshot snapshot(List<AccountSnapshot.Holding> holdings) {
        return new AccountSnapshot(15_578.07, 9_822.44, 5_755.63, 0, holdings, 1_787_400_000L);
    }

    @Test
    @DisplayName("a broker reading stores cash, margin and holdings at market")
    void storesBrokerReading() {
        repository.record(MONDAY, snapshot(List.of(
                new AccountSnapshot.Holding("RELIANCE", "INE002A01018", 127, 1394.88, 1313.10),
                new AccountSnapshot.Holding("HDFCBANK", "INE040A01034", 351, 666.54, 700.80))));

        AccountBalanceRepository.Balance balance = repository.latestOnOrBefore(MONDAY).orElseThrow();
        assertEquals(15_578.07, balance.cash(), 0.005);
        assertEquals(9_822.44, balance.marginUsed(), 0.005);
        assertEquals(5_755.63, balance.available(), 0.005);
        assertTrue(balance.fromBroker());
        assertEquals(0, balance.unpricedHoldings());

        double expectedHoldings = 127 * 1313.10 + 351 * 700.80;
        assertEquals(expectedHoldings, balance.invested(), 0.005);
        assertEquals(15_578.07 + expectedHoldings, balance.total(), 0.005);
        assertEquals(2, rows("SELECT COUNT(*) FROM account_holding WHERE session_date = '" + MONDAY + "'"));
    }

    @Test
    @DisplayName("an unpriced holding counts at cost and is flagged, not silently dropped")
    void unpricedHoldingCountsAtCost() {
        repository.record(MONDAY, snapshot(List.of(
                new AccountSnapshot.Holding("RELIANCE", "INE002A01018", 10, 1394.88, null))));

        AccountBalanceRepository.Balance balance = repository.latestOnOrBefore(MONDAY).orElseThrow();
        assertEquals(1, balance.unpricedHoldings());
        assertEquals(10 * 1394.88, balance.invested(), 0.005);
    }

    @Test
    @DisplayName("re-reading the same day replaces the holdings rather than accumulating them")
    void rereadingReplacesHoldings() {
        repository.record(MONDAY, snapshot(List.of(
                new AccountSnapshot.Holding("RELIANCE", "INE002A01018", 127, 1394.88, 1313.10),
                new AccountSnapshot.Holding("HDFCBANK", "INE040A01034", 351, 666.54, 700.80))));
        // RELIANCE sold during the day: the second reading must not leave it behind.
        repository.record(MONDAY, snapshot(List.of(
                new AccountSnapshot.Holding("HDFCBANK", "INE040A01034", 351, 666.54, 705.00))));

        assertEquals(1, rows("SELECT COUNT(*) FROM account_holding WHERE session_date = '" + MONDAY + "'"));
        assertEquals(1, rows("SELECT COUNT(*) FROM account_balance"));
        assertEquals(351 * 705.00, repository.latestOnOrBefore(MONDAY).orElseThrow().invested(), 0.005);
    }

    @Test
    @DisplayName("a hand-entered balance stays distinguishable from a reading")
    void manualEntryHasNoBrokerFields() {
        repository.record(MONDAY, 250_000, 50_000.0, "manual");

        AccountBalanceRepository.Balance balance = repository.latestOnOrBefore(MONDAY).orElseThrow();
        assertFalse(balance.fromBroker());
        assertNull(balance.marginUsed());
        assertNull(balance.available());
        assertNull(balance.unpricedHoldings());
        assertEquals(300_000, balance.total(), 0.005);
    }

    @Test
    @DisplayName("v_account_equity prefers the reading, carries it forward, and says which")
    void accountEquityPrefersTheReading() {
        repository.record(MONDAY, snapshot(List.of(
                new AccountSnapshot.Holding("HDFCBANK", "INE040A01034", 100, 666.54, 700.00))));

        String[] row = database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT account_value, basis FROM v_account_equity "
                         + "WHERE session_date = '" + MONDAY + "'")) {
                return rs.next() ? new String[] {rs.getString(1), rs.getString(2)} : null;
            }
        });

        assertNotNull(row, "a day with a balance but no trades must still appear");
        assertEquals(15_578.07 + 70_000, Double.parseDouble(row[0]), 0.005);
        assertEquals("broker", row[1]);
    }

    @Test
    @DisplayName("v_account_equity labels a hand-entered day 'manual', not 'broker'")
    void accountEquityNamesTheProvenance() {
        repository.record(MONDAY, 250_000, 50_000.0, "manual");
        repository.record(MONDAY.plusDays(1), snapshot(List.of()));

        assertEquals("manual", basisOn(MONDAY));
        assertEquals("broker", basisOn(MONDAY.plusDays(1)));
    }

    private String basisOn(LocalDate date) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT basis FROM v_account_equity WHERE session_date = '" + date + "'")) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private long rows(String sql) {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }
}
