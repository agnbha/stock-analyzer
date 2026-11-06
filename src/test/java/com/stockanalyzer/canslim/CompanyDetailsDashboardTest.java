package com.stockanalyzer.canslim;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.store.CompanyProfileRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteCompanyProfileRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import com.stockanalyzer.util.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Company Details page: everything read from a company's screener.in page, for one symbol at a time. Each check
 * runs a panel's own query over profiles saved the way {@code CanSlimMain profiles} saves them.
 *
 * <p>The expected figures for WELSPUNLIV come from its quarterly table as the page prints it (Sep 2026: sales 2,838,
 * net profit 170, EPS 1.79; Sep 2025: 2,441, 15, 0.14; Jun 2026: 2,795, 163) and its shareholding (Sep 2026:
 * promoters 63.77, FII 9.07, DII 12.09, public 14.71, 203,526 holders; Jun 2026: 66.36, 5.21, 11.38, 16.69, 223,860;
 * Sep 2025: 66.24, 4.97, 8.58, 19.84, 273,918).
 */
class CompanyDetailsDashboardTest {

    private static final long NOW = 1_793_000_000L;
    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "07-company-details.json");

    @TempDir
    Path directory;

    private Database database;
    private CompanyProfileRepository profiles;
    private SqliteInstrumentRepository instruments;
    private CompanyProfile welspun;

    @BeforeEach
    void setUp() throws IOException {
        database = TestDatabase.open(directory);
        profiles = new SqliteCompanyProfileRepository(database);
        instruments = new SqliteInstrumentRepository(database);
        try (InputStream in = getClass().getResourceAsStream("/screener/company-welspunliv.html")) {
            welspun = ScreenerCompanyParser.profile(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("the tiles show the company at the latest price, and its latest quarter against the same one a year earlier")
    void tiles() throws IOException {
        profiles.save(id("WELSPUNLIV"), welspun, NOW);

        Map<String, Object> about = only(panel(1, "WELSPUNLIV"));
        assertTrue(((String) about.get("About")).startsWith("Welspun Living Limited"));
        assertEquals("Sep 2026", about.get("Latest results"));
        assertEquals("Sep 2026", about.get("Shareholding as of"));

        Map<String, Object> valuation = only(panel(2, "WELSPUNLIV"));
        assertEquals(14, valuation.size(), "one tile per figure");
        assertEquals(254.0, number(valuation, "Price"), 1e-9, "with no price of our own, the page's");
        assertEquals(23_968.0, number(valuation, "Market cap (₹ Cr)"), 0.5);
        assertEquals(55.6, number(valuation, "P/E"), 1e-9, "254 / 4.57, to one decimal");

        Map<String, Object> growth = only(panel(3, "WELSPUNLIV"));
        assertEquals(24.0, number(growth, "Profit growth (TTM)"), 1e-9, "as screener.in prints it");
        assertEquals(16.3, number(growth, "Quarter revenue, YoY"), 1e-9, "2,838 against 2,441");
        assertEquals(1033.3, number(growth, "Quarter profit, YoY"), 1e-9, "170 against 15");
        assertEquals(6.0, number(growth, "Quarter net margin"), 1e-9, "170 of 2,838");
    }

    @Test
    @DisplayName("each quarter is set against the same quarter a year earlier and the one before it, newest first")
    void quarterlyTable() throws IOException {
        profiles.save(id("WELSPUNLIV"), welspun, NOW);

        List<Map<String, Object>> rows = panel(7, "WELSPUNLIV");
        assertEquals(8, rows.size());
        assertEquals("Sep 2026", rows.getFirst().get("Quarter"));
        assertEquals("Dec 2024", rows.getLast().get("Quarter"));

        Map<String, Object> latest = rows.getFirst();
        assertEquals(5.99, number(latest, "Net margin"), 1e-9);
        assertEquals(16.26, number(latest, "Revenue YoY"), 1e-9);
        assertEquals(1033.33, number(latest, "Profit YoY"), 1e-9);
        assertEquals(1178.57, number(latest, "EPS YoY"), 1e-9, "1.79 against 0.14");
        assertEquals(1.54, number(latest, "Revenue QoQ"), 1e-9, "2,838 against 2,795");
        assertEquals(4.29, number(latest, "Profit QoQ"), 1e-9, "170 against 163");

        Map<String, Object> dec2025 = rows.get(3);
        assertEquals("Dec 2025", dec2025.get("Quarter"));
        assertEquals(-100.0, number(dec2025, "EPS YoY"), 1e-9, "0.00 against 1.26: a fall to nothing is -100%");

        for (Map<String, Object> early : rows.subList(4, 8)) {
            assertNull(early.get("Revenue YoY"), early.get("Quarter") + " has no year-earlier quarter among the eight");
        }
        assertNull(rows.getLast().get("Revenue QoQ"), "nor the oldest a quarter before it");

        List<Map<String, Object>> bars = panel(4, "WELSPUNLIV");
        assertEquals("Dec 2024", bars.getFirst().get("Quarter"), "the charts run oldest to newest");
        assertEquals(8, panel(5, "WELSPUNLIV").size());
        assertEquals(5.99, number(panel(6, "WELSPUNLIV").getLast(), "Net margin"), 1e-9);
    }

    @Test
    @DisplayName("a change against a loss, or against a quarter that was not read, is blank rather than paired with the wrong quarter")
    void changesNeedAComparableQuarter() throws IOException {
        // No Sep 2025: the company's page skipped it, or it fell outside the quarters read.
        profiles.save(id("TURN"), company("Industrials › Construction › Civil Construction", 1_000.0, List.of(
                quarter("2025-03", 100, -10, -1.0),
                quarter("2025-06", 110, 5, 0.5),
                quarter("2025-12", 120, 6, 0.6),
                quarter("2026-03", 130, 13, 1.3),
                quarter("2026-06", 140, 14, 1.4))), NOW);

        Map<String, Map<String, Object>> byQuarter = new LinkedHashMap<>();
        panel(7, "TURN").forEach(row -> byQuarter.put((String) row.get("Quarter"), row));

        Map<String, Object> mar2026 = byQuarter.get("Mar 2026");
        assertEquals(30.0, number(mar2026, "Revenue YoY"), 1e-9, "130 against 100");
        assertNull(mar2026.get("Profit YoY"), "a year earlier it lost money: no percentage of a loss");
        assertNull(mar2026.get("EPS YoY"));
        assertEquals(8.33, number(mar2026, "Revenue QoQ"), 1e-9, "130 against Dec 2025's 120");

        assertEquals(180.0, number(byQuarter.get("Jun 2026"), "Profit YoY"), 1e-9, "14 against 5");
        Map<String, Object> dec2025 = byQuarter.get("Dec 2025");
        assertNull(dec2025.get("Revenue QoQ"), "Sep 2025 is missing; Jun 2025 is not the quarter before");
        assertNull(dec2025.get("Profit QoQ"));
        assertEquals(-10.0, number(byQuarter.get("Mar 2025"), "Net margin"), 1e-9, "a loss is a negative margin");

        Map<String, Object> growth = only(panel(3, "TURN"));
        assertEquals(27.3, number(growth, "Quarter revenue, YoY"), 1e-9, "the latest quarter, Jun 2026: 140 against 110");
        assertEquals(180.0, number(growth, "Quarter profit, YoY"), 1e-9);
    }

    @Test
    @DisplayName("holdings change in percentage points over the quarter and the year; the bars add up to the whole")
    void shareholding() throws IOException {
        profiles.save(id("WELSPUNLIV"), welspun, NOW);

        Map<String, Object> quarter = only(panel(10, "WELSPUNLIV"));
        assertEquals(-2.59, number(quarter, "Promoters"), 1e-9, "63.77 from 66.36");
        assertEquals(3.86, number(quarter, "FII"), 1e-9);
        assertEquals(0.71, number(quarter, "DII"), 1e-9);
        assertEquals(-1.98, number(quarter, "Public"), 1e-9);
        assertEquals(-9.1, number(quarter, "Shareholders"), 1e-9, "203,526 from 223,860, in percent");

        Map<String, Object> year = only(panel(11, "WELSPUNLIV"));
        assertEquals(-2.47, number(year, "Promoters"), 1e-9, "63.77 from Sep 2025's 66.24");
        assertEquals(4.10, number(year, "FII"), 1e-9);
        assertEquals(3.51, number(year, "DII"), 1e-9);
        assertEquals(-5.13, number(year, "Public"), 1e-9);
        assertEquals(-25.7, number(year, "Shareholders"), 1e-9, "203,526 from 273,918");

        Map<String, Object> latest = panel(8, "WELSPUNLIV").getLast();
        double whole = 0;
        for (String part : new String[]{"Promoters", "FII", "DII", "Public", "Government and others"}) {
            whole += number(latest, part);
        }
        assertEquals(100.0, whole, 0.02, "with government and others in, a bar is the whole company");
        assertEquals(203_526.0, number(panel(9, "WELSPUNLIV").getLast(), "Shareholders"), 1e-9);
        List<Map<String, Object>> table = panel(12, "WELSPUNLIV");
        assertEquals(8, table.size());
        assertEquals("Sep 2026", table.getFirst().get("Quarter"), "newest first");
    }

    @Test
    @DisplayName("the comparison lists the symbol, then its industry, then its sector, each largest first, and skips pages with no figures")
    void comparison() throws IOException {
        profiles.save(id("WELSPUNLIV"), welspun, NOW);
        profiles.save(id("SMALLTEX"), company("Consumer Discretionary › Textiles › Textiles & Apparels › Garments & Apparels", 3_000.0, List.of()), NOW);
        profiles.save(id("BIGTEX"), company("Consumer Discretionary › Textiles › Textiles & Apparels › Garments & Apparels", 30_000.0, List.of()), NOW);
        profiles.save(id("CARS"), company("Consumer Discretionary › Automobile and Auto Components › Automobiles › 2/3 Wheelers", 9_000.0, List.of()), NOW);
        profiles.save(id("BANK"), company("Financial Services › Banks › Private Sector Bank", 900_000.0, List.of()), NOW);
        profiles.save(id("FMCG"), company("Fast Moving Consumer Goods › Diversified FMCG", 500_000.0, List.of()), NOW);
        profiles.saveEmpty(id("NIFTYBEES"), NOW);

        List<Map<String, Object>> rows = panel(13, "WELSPUNLIV");
        List<String> order = rows.stream().map(r -> (String) r.get("Symbol")).toList();
        assertEquals(List.of("WELSPUNLIV", "BIGTEX", "SMALLTEX", "CARS", "BANK", "FMCG"), order,
                "the symbol, its industry group by size, its sector, then the rest by size; no ETF");
        List<Object> peers = rows.stream().map(r -> r.get("Peer")).toList();
        assertEquals(Arrays.asList("selected", "same industry", "same industry", "same sector", null, null), peers);

        assertEquals("Other Textile Products", rows.getFirst().get("Industry"), "the last level of the classification");
        assertEquals("Diversified FMCG", rows.getLast().get("Industry"), "however many levels it has");
        assertEquals(16.3, number(rows.getFirst(), "Rev YoY"), 1e-9, "the same latest-quarter figures as the tiles");
        assertEquals(63.77, number(rows.getFirst(), "Promoters"), 1e-9, "the latest shareholding");

        List<String> fromABank = panel(13, "BANK").stream().map(r -> (String) r.get("Symbol")).toList();
        assertEquals("BANK", fromABank.getFirst(), "whichever symbol is picked comes first");
        assertEquals(6, fromABank.size());
    }

    @Test
    @DisplayName("an ETF is listed with a word on why its page is empty; a symbol never read is not listed")
    void anEtfAndAnUnreadSymbol() throws IOException {
        profiles.saveEmpty(id("NIFTYBEES"), NOW);
        id("NEVERREAD");

        String symbolQuery = symbolQuery();
        List<String> symbols = database.read(connection -> {
            List<String> listed = new ArrayList<>();
            try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(symbolQuery)) {
                while (rs.next()) {
                    listed.add(rs.getString(1));
                }
            }
            return listed;
        });
        assertEquals(List.of("NIFTYBEES"), symbols);

        assertTrue(((String) only(panel(1, "NIFTYBEES")).get("About")).contains("no company figures"));
        for (int id : new int[]{4, 5, 6, 7, 8, 9, 10, 11, 12}) {
            assertTrue(panel(id, "NIFTYBEES").isEmpty(), "panel " + id);
        }
        assertTrue(panel(13, "NIFTYBEES").isEmpty(), "and the comparison has nothing to list");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private long id(String symbol) {
        return instruments.findOrCreate(symbol, "NSE", "CASH");
    }

    private static CompanyProfile.Quarter quarter(String period, double sales, double netProfit, double eps) {
        return new CompanyProfile.Quarter(YearMonth.parse(period), sales, netProfit, eps);
    }

    private static CompanyProfile company(String classification, double marketCapCr, List<CompanyProfile.Quarter> quarters) {
        YearMonth latest = quarters.isEmpty() ? null : quarters.getLast().period();
        return new CompanyProfile("A company.", classification, marketCapCr, 100.0, 120.0, 80.0, 20.0, 50.0, 1.0,
                15.0, 12.0, 1.0, 5.0, 0.5, 10.0, 10.0, 10.0, 10.0, latest, null, quarters, List.of());
    }

    private static double number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        assertTrue(value instanceof Number, column + " was " + value + " in " + row);
        return ((Number) value).doubleValue();
    }

    private static Map<String, Object> only(List<Map<String, Object>> rows) {
        assertEquals(1, rows.size(), rows.toString());
        return rows.getFirst();
    }

    private static JsonNode dashboard() throws IOException {
        return JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
    }

    private static String symbolQuery() throws IOException {
        for (JsonNode variable : dashboard().path("templating").path("list")) {
            if (variable.path("name").asText().equals("symbol")) {
                return variable.path("query").asText();
            }
        }
        throw new AssertionError("no symbol picker");
    }

    private List<Map<String, Object>> panel(int id, String symbol) throws IOException {
        JsonNode target = StreamSupport.stream(dashboard().path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == id).findFirst().orElseThrow()
                .path("targets").get(0);
        String sql = target.path("rawQueryText").asText();
        assertEquals(sql, target.path("queryText").asText(), "panel " + id + ": the editor and the datasource read the same SQL");
        return rows(sql.replace("$symbol", symbol));
    }

    private List<Map<String, Object>> rows(String sql) {
        return database.read(connection -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        });
    }
}
