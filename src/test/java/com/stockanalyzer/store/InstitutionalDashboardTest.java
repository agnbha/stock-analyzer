package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.markets.MarketSources;
import com.stockanalyzer.markets.Observation;
import com.stockanalyzer.store.jdbc.SqliteMarketSeriesRepository;
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
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Institutional Investment, the page linked from CAN SLIM's header, by running its own queries over readings stored the
 * way {@code CanSlimMain markets} stores them. The readings are invented.
 */
class InstitutionalDashboardTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");
    private static final String PAGE = "09-institutional.json";
    private static final Pattern BUTTON = Pattern.compile("<a href=\"([^\"]+)\" target=\"_blank\"[^>]*>([^<]+)</a>");

    @TempDir
    Path directory;

    private Database database;
    private SqliteMarketSeriesRepository repository;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        repository = new SqliteMarketSeriesRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private void store(String series, String... readings) {
        List<Observation> observations = new ArrayList<>();
        for (String reading : readings) {
            String[] parts = reading.split("=");
            observations.add(new Observation(LocalDate.parse(parts[0]), Double.parseDouble(parts[1])));
        }
        repository.upsert(series, observations, 1_795_008_600L);
    }

    @Test
    @DisplayName("each flow's latest day and its date, then its sums over five days, the month and the year")
    void latestFlows() throws IOException {
        store("FII_CASH_NET", "2026-11-17=1000", "2026-11-18=-500.4");
        store("DII_CASH_NET", "2026-11-18=1200.4");
        store("FPI_EQUITY", "2025-12-31=999", "2026-10-30=300", "2026-11-02=100", "2026-11-03=200",
                "2026-11-04=-50", "2026-11-05=25", "2026-11-06=10", "2026-11-09=5");

        List<Map<String, Object>> rows = rows(queries(2).getFirst());

        assertEquals(List.of("FII/FPI in the cash market (NSE, provisional)", "DII in the cash market (NSE, provisional)",
                "FPI equity, every route (NSDL)"), rows.stream().map(row -> row.get("Flow")).toList(),
                "in the page's order; a flow with nothing stored has no row");
        Map<String, Object> fii = rows.getFirst();
        assertEquals("18 Nov 2026", fii.get("As of"));
        assertEquals(-500.0, number(fii, "Latest day"));
        assertEquals(500.0, number(fii, "Last 5 days"), "the two days held");
        assertEquals(1200.0, number(rows.get(1), "Latest day"));

        Map<String, Object> fpi = rows.get(2);
        assertEquals("9 Nov 2026", fpi.get("As of"));
        assertEquals(5.0, number(fpi, "Latest day"));
        assertEquals(190.0, number(fpi, "Last 5 days"), "200 - 50 + 25 + 10 + 5");
        assertEquals(290.0, number(fpi, "Month to date"), "November's six days");
        assertEquals(590.0, number(fpi, "Year to date"), "October's 300 too, not last December's");
    }

    @Test
    @DisplayName("FII and DII cash-market bars sit side by side, a day at a time")
    void cashMarketBars() throws IOException {
        store("FII_CASH_NET", "2026-11-17=1000", "2026-11-18=-500");
        store("DII_CASH_NET", "2026-11-18=1200");

        List<String> sql = queries(3);
        List<Map<String, Object>> fii = rows(sql.get(0));
        assertEquals(2, fii.size());
        assertEquals(LocalDate.of(2026, 11, 17).toEpochDay() * 86_400, ((Number) fii.getFirst().get("time")).longValue());
        assertEquals(-500.0, number(fii.getLast(), "FII/FPI"));
        assertEquals(1200.0, number(rows(sql.get(1)).getFirst(), "DII"));

        Map<String, Integer> alignment = new LinkedHashMap<>();
        for (JsonNode override : panelNode(3).path("fieldConfig").path("overrides")) {
            for (JsonNode property : override.path("properties")) {
                if (property.path("id").asText().equals("custom.barAlignment")) {
                    alignment.put(override.path("matcher").path("options").asText(), property.path("value").asInt());
                }
            }
        }
        assertEquals(Map.of("FII/FPI", -1, "DII", 1), alignment, "one bar each side of the day, not on top");
    }

    @Test
    @DisplayName("FPI equity is added up by month, on the exchanges and through the primary market apart")
    void monthlyEquity() throws IOException {
        store("FPI_EQUITY_EXCHANGE", "2026-10-29=-300", "2026-10-30=100", "2026-11-02=50.6");
        store("FPI_EQUITY_PRIMARY", "2026-11-02=20");

        List<Map<String, Object>> exchange = rows(queries(4).get(0));
        assertEquals(2, exchange.size());
        assertEquals(LocalDate.of(2026, 10, 1).toEpochDay() * 86_400,
                ((Number) exchange.getFirst().get("time")).longValue(), "drawn on the month's first day");
        assertEquals(-200.0, number(exchange.getFirst(), "On the exchanges"));
        assertEquals(51.0, number(exchange.getLast(), "On the exchanges"), "November so far");
        assertEquals(20.0, number(rows(queries(4).get(1)).getFirst(), "Primary market and others"));
        assertEquals("normal", panelNode(4).path("fieldConfig").path("defaults").path("custom").path("stacking")
                .path("mode").asText(), "stacked: the month's total is the bar's height");
    }

    @Test
    @DisplayName("FPI equity a day, with the sum of the last 20 reporting days beside it")
    void dailyEquityAndTwentyDays() throws IOException {
        String[] readings = new String[21];
        LocalDate day = LocalDate.of(2026, 10, 1);
        for (int i = 0; i < readings.length; i++) {
            readings[i] = day.plusDays(i) + "=" + (i + 1);
        }
        store("FPI_EQUITY", readings);

        List<Map<String, Object>> rows = rows(queries(5).getFirst());
        assertEquals(21, rows.size());
        assertEquals(1.0, number(rows.getFirst(), "20 days"), "the first day has only itself");
        assertEquals(21.0, number(rows.getLast(), "A day"));
        assertEquals(230.0, number(rows.getLast(), "20 days"), "2 to 21: the first day has dropped out");
    }

    @Test
    @DisplayName("FPI debt is added up by month")
    void monthlyDebt() throws IOException {
        store("FPI_DEBT", "2026-10-29=-300.2", "2026-10-30=1000", "2026-11-02=-40");

        List<Map<String, Object>> debt = rows(queries(6).getFirst());
        assertEquals(List.of(700.0, -40.0), debt.stream().map(row -> number(row, "Debt")).toList());
    }

    @Test
    @DisplayName("each side's share of its index futures held long, on days with both sides")
    void indexFuturesLongShare() throws IOException {
        store("IDX_FUT_FII_LONG", "2026-11-17=100", "2026-11-18=400");
        store("IDX_FUT_FII_SHORT", "2026-11-18=1200");
        store("IDX_FUT_DII_LONG", "2026-11-18=300");
        store("IDX_FUT_DII_SHORT", "2026-11-18=100");

        List<String> sql = queries(7);
        assertEquals(4, sql.size(), "FII, DII, proprietary and clients");
        List<Map<String, Object>> fii = rows(sql.get(0));
        assertEquals(1, fii.size(), "17 Nov has no short side stored");
        assertEquals(25.0, number(fii.getFirst(), "FII"));
        assertEquals(75.0, number(rows(sql.get(1)).getFirst(), "DII"));
        assertTrue(rows(sql.get(3)).isEmpty(), "no clients' positions stored: an empty line, not an error");
        assertEquals("percent", panelNode(7).path("fieldConfig").path("defaults").path("unit").asText());
    }

    @Test
    @DisplayName("every series the page reads is one the markets step stores")
    void readsOnlyStoredSeries() throws IOException {
        Matcher series = Pattern.compile("'((?:FPI|FII|DII|IDX_FUT)_[A-Z_]+)'")
                .matcher(Files.readString(DASHBOARDS.resolve(PAGE)));
        int found = 0;
        while (series.find()) {
            found++;
            assertTrue(MarketSources.INSTITUTIONAL.contains(series.group(1)), series.group(1));
        }
        assertTrue(found > 20, "found " + found);
    }

    @Test
    @DisplayName("the buttons open the sources' own pages in a new tab")
    void sourceButtons() throws IOException {
        Matcher button = BUTTON.matcher(panelNode(1).path("options").path("content").asText());
        List<String> links = new ArrayList<>();
        while (button.find()) {
            links.add(button.group(1));
        }
        assertEquals(List.of("https://www.nseindia.com/reports/fii-dii",
                "https://www.fpi.nsdl.co.in/web/Reports/Latest.aspx",
                "https://www.fpi.nsdl.co.in/web/Reports/Archive.aspx"), links);
    }

    @Test
    @DisplayName("CAN SLIM's header opens this page on the same range, and this page's header leads back")
    void linkedFromCanSlim() throws IOException {
        JsonNode canslim = dashboard("06-canslim.json");
        JsonNode page = dashboard(PAGE);
        assertEquals("Institutional Investment", page.path("title").asText());
        JsonNode there = canslim.path("links").get(1);
        assertEquals("Bonds and Commodities", canslim.path("links").get(0).path("title").asText(), "still first");
        assertEquals(page.path("title").asText(), there.path("title").asText());
        assertTrue(there.path("url").asText().startsWith("/d/" + page.path("uid").asText() + "/"));
        assertTrue(there.path("keepTime").asBoolean());
        assertFalse(there.path("includeVars").asBoolean(), "the symbol picked means nothing here");

        assertTrue(page.path("links").get(0).path("url").asText()
                .startsWith("/d/" + canslim.path("uid").asText() + "/"));
        assertTrue(page.path("links").get(1).path("url").asText()
                .startsWith("/d/" + dashboard("08-markets.json").path("uid").asText() + "/"));
        assertEquals(0, page.path("templating").path("list").size(), "no symbol and no account to pick");
    }

    private static double number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).doubleValue();
    }

    private List<String> queries(int id) throws IOException {
        List<String> sql = new ArrayList<>();
        for (JsonNode target : panelNode(id).path("targets")) {
            assertEquals(target.path("rawQueryText").asText(), target.path("queryText").asText());
            sql.add(target.path("rawQueryText").asText());
        }
        return sql;
    }

    private JsonNode panelNode(int id) throws IOException {
        for (JsonNode panel : dashboard(PAGE).path("panels")) {
            if (panel.path("id").asInt() == id) {
                return panel;
            }
        }
        throw new AssertionError("no panel " + id);
    }

    private static JsonNode dashboard(String name) throws IOException {
        return JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARDS.resolve(name)));
    }

    private List<Map<String, Object>> rows(String sql) {
        return database.read(connection -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= meta.getColumnCount(); c++) {
                        row.put(meta.getColumnLabel(c), rs.getObject(c));
                    }
                    rows.add(row);
                }
            }
            return rows;
        });
    }
}
