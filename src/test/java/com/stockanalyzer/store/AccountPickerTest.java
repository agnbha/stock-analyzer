package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.model.AccountSnapshot;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.jdbc.SqliteAccountBalanceRepository;
import com.stockanalyzer.store.jdbc.SqliteAccountProfileRepository;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Account box on the pages that show buys, sells and account figures: one Groww account at a time by default, the
 * first, and the stock data the same whichever is picked. The amounts are invented.
 */
class AccountPickerTest {

    private static final List<String> PAGES = List.of("01-overview.json", "02-candles-and-calls.json",
            "03-decision-reasons.json");
    /** Tables and views whose rows belong to an account. */
    private static final Pattern ACCOUNT_DATA = Pattern.compile("\\b(trade|realized_lot|open_position|pnl_period|"
            + "account_balance|account_holding|v_daily_pnl|v_trade_markers|v_current_holdings|v_account_equity|"
            + "v_account_value|v_equity_curve|v_reason_frequency|v_bet)\\b");
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
    @DisplayName("each page has an Account box listing the accounts, first first, opening on the first")
    void theBox() throws IOException {
        for (String page : PAGES) {
            JsonNode box = variables(page).stream().filter(v -> v.path("name").asText().equals("profile"))
                    .findFirst().orElseThrow(() -> new AssertionError(page + " has no Account box"));
            assertEquals("Account", box.path("label").asText(), page);
            assertEquals("SELECT name FROM account_profile ORDER BY position, name", box.path("query").asText(), page);
            assertTrue(box.path("multi").asBoolean(), page + ": more than one can be ticked, to add them up");
            assertFalse(box.path("includeAll").asBoolean(), page + ": an All entry would come first and be the default");
            assertEquals("", box.path("current").path("value").asText(),
                    page + ": no account stored in the page, so it opens on the first listed");
            assertEquals(0, box.path("hide").asInt(), page);
        }
    }

    @Test
    @DisplayName("every query reading an account's rows is narrowed to the accounts picked")
    void everyAccountQueryIsFiltered() throws IOException {
        List<String> unfiltered = new ArrayList<>();
        int checked = 0;
        for (String page : PAGES) {
            JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(Path.of("grafana", "dashboards", page)));
            List<JsonNode> holders = new ArrayList<>();
            collectPanels(root.path("panels"), holders);
            for (JsonNode annotation : root.path("annotations").path("list")) {
                if (annotation.has("target")) {
                    holders.add(annotation);
                }
            }
            for (JsonNode holder : holders) {
                // "Last data refresh" is when anything at all was last written, whichever account.
                if (holder.path("id").asInt() == 90) {
                    continue;
                }
                List<JsonNode> queries = new ArrayList<>();
                holder.path("targets").forEach(queries::add);
                if (holder.has("target")) {
                    queries.add(holder.path("target"));
                }
                for (JsonNode query : queries) {
                    String sql = query.path("rawQueryText").asText();
                    if (!ACCOUNT_DATA.matcher(sql).find()) {
                        continue;
                    }
                    checked++;
                    if (!sql.contains("${profile:sqlstring}")) {
                        unfiltered.add(page + " " + holder.path("title").asText(holder.path("name").asText()));
                    }
                    assertEquals(sql, query.path("queryText").asText(), page + ": the two copies of a query agree");
                }
            }
        }
        assertTrue(checked > 20, "found the account queries: " + checked);
        assertEquals(List.of(), unfiltered);
    }

    @Test
    @DisplayName("a stock's calls are the picked account's; ticking both shows both, each named")
    void callsOfThePickedAccount() throws IOException {
        long first = buy("first", "f1", 100.0);
        long second = buy("second", "s1", 101.0);

        String sql = panelSql("02-candles-and-calls.json", 12).replace("$symbol", "INFY")
                .replace("${session:sqlstring}", "'" + DAY + "'");
        assertEquals(List.of("100.0|first"), rows(sql.replace("${profile:sqlstring}", "'first'")));
        assertEquals(List.of("101.0|second"), rows(sql.replace("${profile:sqlstring}", "'second'")));
        assertEquals(2, rows(sql.replace("${profile:sqlstring}", "'first','second'")).size());
        assertTrue(first > 0 && second > first);
    }

    @Test
    @DisplayName("the account value adds up each picked account at its own latest reading")
    void accountValueAddsUp() throws IOException {
        new SqliteAccountBalanceRepository(database, "first").record(DAY.minusDays(3), snapshot(900));
        new SqliteAccountBalanceRepository(database, "first").record(DAY, snapshot(1000));
        new SqliteAccountBalanceRepository(database, "second").record(DAY.minusDays(1), snapshot(500));
        new SqliteAccountProfileRepository(database).register(List.of("first", "second"));

        String sql = panelSql("01-overview.json", 10);
        assertEquals(List.of("1000.0"), rows(sql.replace("${profile:sqlstring}", "'first'")));
        assertEquals(List.of("1500.0"), rows(sql.replace("${profile:sqlstring}", "'first','second'")),
                "the second account's latest is a day older, and still counts");
        assertEquals(List.of("first", "second"), rows("SELECT name FROM account_profile ORDER BY position, name"));
    }

    private static AccountSnapshot snapshot(double cash) {
        return new AccountSnapshot(cash, 0, cash, 0, List.of(), 1L);
    }

    private long buy(String profile, String id, double price) {
        long at = DAY.toEpochDay() * 86_400L + 4 * 3_600L;
        new SqliteTradeRepository(database, profile).importAll(List.of(new Trade(0, "INFY", id, "o-" + id, DAY, at,
                Side.BUY, 10, price, Product.MIS, 0, null, Trade.ChargesSource.MODELLED, Trade.TradeSource.BROKER,
                null)), "NSE", "CASH");
        return new SqliteTradeRepository(database, profile).findAllOrdered().getFirst().id();
    }

    private static List<JsonNode> variables(String page) throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(Path.of("grafana", "dashboards", page)));
        List<JsonNode> list = new ArrayList<>();
        root.path("templating").path("list").forEach(list::add);
        return list;
    }

    private static void collectPanels(JsonNode panels, List<JsonNode> into) {
        for (JsonNode panel : panels) {
            into.add(panel);
            collectPanels(panel.path("panels"), into);
        }
    }

    private static String panelSql(String page, int id) throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(Path.of("grafana", "dashboards", page)));
        List<JsonNode> panels = new ArrayList<>();
        collectPanels(root.path("panels"), panels);
        return panels.stream().filter(p -> p.path("id").asInt() == id).findFirst().orElseThrow()
                .path("targets").get(0).path("rawQueryText").asText();
    }

    /** Each row's "Price" and "Account" when the query has them, else its first column. */
    private List<String> rows(String sql) {
        assertFalse(sql.contains("${"), "an unfilled variable would make this prove nothing: " + sql);
        return database.read(connection -> {
            List<String> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                boolean calls = rs.getMetaData().getColumnCount() > 5;
                while (rs.next()) {
                    rows.add(calls ? rs.getDouble("Price") + "|" + rs.getString("Account") : rs.getString(1));
                }
            }
            return rows;
        });
    }
}
