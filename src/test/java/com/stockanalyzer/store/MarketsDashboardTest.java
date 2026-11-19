package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bonds and Commodities, the page linked from CAN SLIM's header, by running its own queries over readings stored the
 * way {@code CanSlimMain markets} stores them. The readings are invented.
 */
class MarketsDashboardTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");
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
    @DisplayName("the latest reading of each series, its date, and its move: basis points for a yield, percent for a price")
    void latestReadings() throws IOException {
        store("US10Y", "2025-11-14=4.10", "2026-08-14=4.60", "2026-10-15=4.90", "2026-10-16=4.95", "2026-11-16=5.12");
        store("US2Y", "2026-10-16=4.70", "2026-11-16=4.83");
        store("BRENT", "2026-10-10=80.00", "2026-11-11=92.00");
        store("WTI", "2026-11-11=89.62");
        store("MCX_CRUDEOIL", "2026-10-16=6400", "2026-11-17=7040.4");
        store("MCX_GOLD", "2026-10-16=100000", "2026-11-17=110000");
        store("MCX_SILVER", "2026-11-17=150000");

        List<Map<String, Object>> rows = rows(panel(2));

        assertEquals(List.of("US 10-year Treasury yield", "US 2-year Treasury yield", "Brent crude (USD a barrel)",
                "WTI crude (USD a barrel)", "MCX crude oil (₹ a barrel)", "MCX gold (₹ a troy ounce)",
                "MCX silver (₹ a troy ounce)"), rows.stream().map(row -> row.get("Series")).toList());
        Map<String, Object> ten = rows.getFirst();
        assertEquals("5.12%", ten.get("Latest"));
        assertEquals("16 Nov 2026", ten.get("As of"));
        assertEquals("+17 bp", ten.get("Over a month"), "against 16 Oct's 4.95, the last on or before a month back");
        assertEquals("+52 bp", ten.get("Over 3 months"), "against 14 Aug's 4.60");
        assertEquals("+102 bp", ten.get("Over a year"), "against 14 Nov 2025's 4.10");
        assertEquals("+13 bp", rows.get(1).get("Over a month"));
        assertNull(rows.get(1).get("Over a year"), "no reading a year back");

        Map<String, Object> brent = rows.get(2);
        assertEquals("92.00", brent.get("Latest"));
        assertEquals("11 Nov 2026", brent.get("As of"), "crude's own date, a week behind the yields");
        assertEquals("+15.0%", brent.get("Over a month"), "92 against 80");
        assertNull(rows.get(3).get("Over a month"));

        assertEquals("7,040", rows.get(4).get("Latest"), "rupees a barrel, as MCX quotes it");
        assertEquals("+10.0%", rows.get(4).get("Over a month"));
        assertEquals("17 Nov 2026", rows.get(4).get("As of"));
        assertEquals("342,138", rows.get(5).get("Latest"), "110,000 per 10 grams is 342,138 per troy ounce");
        assertEquals("+10.0%", rows.get(5).get("Over a month"), "the same move whatever the unit");
        assertEquals("4,666", rows.get(6).get("Latest"), "150,000 a kilogram is 4,666 per troy ounce");
    }

    @Test
    @DisplayName("the charts run oldest first, and the 10-year less 2-year gap is drawn only on days both have a reading")
    void charts() throws IOException {
        store("US10Y", "2026-11-12=4.95", "2026-11-13=5.00", "2026-11-16=5.12");
        store("US2Y", "2026-11-12=4.80", "2026-11-16=4.83");
        store("BRENT", "2026-11-10=91.00", "2026-11-11=92.00");

        List<String> yields = queries(3);
        assertEquals(3, yields.size(), "10-year, 2-year and the gap");
        List<Map<String, Object>> tenYear = rows(yields.getFirst());
        assertEquals(3, tenYear.size());
        assertEquals(LocalDate.of(2026, 11, 12).toEpochDay() * 86_400, ((Number) tenYear.getFirst().get("time")).longValue());
        List<Map<String, Object>> gap = rows(yields.get(2));
        assertEquals(2, gap.size(), "13 Nov has no 2-year reading");
        assertEquals(0.29, ((Number) gap.getLast().get("10-year less 2-year")).doubleValue(), 1e-9);

        List<Map<String, Object>> brent = rows(queries(4).getFirst());
        assertEquals(92.0, ((Number) brent.getLast().get("Brent")).doubleValue());
        assertTrue(rows(queries(4).get(1)).isEmpty(), "no WTI stored, an empty line rather than an error");
    }

    @Test
    @DisplayName("MCX crude is drawn in rupees a barrel, and gold and silver per troy ounce")
    void commodityCharts() throws IOException {
        store("MCX_CRUDEOIL", "2026-11-16=7000", "2026-11-17=7040");
        store("MCX_GOLD", "2026-11-16=100000", "2026-11-17=110000");
        store("MCX_SILVER", "2026-11-17=150000");

        List<Map<String, Object>> crude = rows(panel(5));
        assertEquals(2, crude.size());
        assertEquals(LocalDate.of(2026, 11, 16).toEpochDay() * 86_400, ((Number) crude.getFirst().get("time")).longValue());
        assertEquals(7040.0, ((Number) crude.getLast().get("MCX crude")).doubleValue());
        assertEquals(311034.77, ((Number) rows(panel(6)).getFirst().get("Gold")).doubleValue(), 1e-9);
        assertEquals(4665.52, ((Number) rows(panel(7)).getFirst().get("Silver")).doubleValue(), 1e-9);
        for (int id : new int[] {5, 6, 7}) {
            assertEquals("locale", panelNode(id).path("fieldConfig").path("defaults").path("unit").asText(),
                    "every digit, grouped the reader's way; the rupee is in the title");
        }
    }

    @Test
    @DisplayName("the buttons open today's prices in a new tab: the yields and crudes on CNBC, MCX's on Groww")
    void liveQuoteButtons() throws IOException {
        JsonNode panel = panelNode(1);
        assertEquals("html", panel.path("options").path("mode").asText());
        Matcher button = BUTTON.matcher(panel.path("options").path("content").asText());
        List<String> titles = new ArrayList<>();
        List<String> links = new ArrayList<>();
        while (button.find()) {
            links.add(button.group(1));
            titles.add(button.group(2));
        }
        assertEquals(List.of("US 10-year yield, live", "US 2-year yield, live", "Brent crude, live",
                "WTI crude, live", "MCX crude, live", "MCX gold, live", "MCX silver, live"), titles);
        assertTrue(links.subList(0, 4).stream().allMatch(link -> link.startsWith("https://www.cnbc.com/quotes/")));
        assertEquals(List.of("https://groww.in/commodities/mcx_crudeoil", "https://groww.in/commodities/mcx_gold",
                "https://groww.in/commodities/mcx_silver"), links.subList(4, 7));
    }

    @Test
    @DisplayName("CAN SLIM's header opens this page on the same range, and this page's header leads back")
    void linkedBothWays() throws IOException {
        JsonNode canslim = dashboard("06-canslim.json");
        JsonNode markets = dashboard("08-markets.json");
        JsonNode there = canslim.path("links").get(0);
        assertEquals("Bonds and Commodities", markets.path("title").asText());
        assertEquals(markets.path("title").asText(), there.path("title").asText());
        assertTrue(there.path("url").asText().startsWith("/d/" + markets.path("uid").asText() + "/"));
        assertTrue(there.path("keepTime").asBoolean(), "the same months either side");
        assertFalse(there.path("includeVars").asBoolean(), "the symbol picked means nothing here");

        JsonNode back = markets.path("links").get(0);
        assertTrue(back.path("url").asText().startsWith("/d/" + canslim.path("uid").asText() + "/"));
        assertEquals(0, markets.path("templating").path("list").size(), "no symbol and no account to pick");
    }

    private List<String> queries(int id) throws IOException {
        List<String> sql = new ArrayList<>();
        for (JsonNode target : panelNode(id).path("targets")) {
            assertEquals(target.path("rawQueryText").asText(), target.path("queryText").asText());
            sql.add(target.path("rawQueryText").asText());
        }
        return sql;
    }

    private String panel(int id) throws IOException {
        return queries(id).getFirst();
    }

    private JsonNode panelNode(int id) throws IOException {
        for (JsonNode panel : dashboard("08-markets.json").path("panels")) {
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
