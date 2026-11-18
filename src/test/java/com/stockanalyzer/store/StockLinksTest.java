package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.util.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The row of buttons under each stock page's main chart, opening the symbol picked on the other stock pages:
 * Candles and Calls under its minute candles, Stock Trend under its daily candles, CAN SLIM under the symbol's chart.
 */
class StockLinksTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");
    private static final Pattern BUTTON = Pattern.compile("<a href=\"([^\"]+)\"[^>]*>([^<]+)</a>");
    private static final Pattern CARRIED = Pattern.compile("\\$\\{(\\w+):queryparam}");
    private static final Pattern STYLE = Pattern.compile("style=\"([^\"]*)\"");
    /**
     * Of a style attribute, what Grafana 11.6's text panel keeps (its sanitizer's CSS list, plus the flex properties
     * Grafana adds to it); anything else is dropped without a word, and the buttons would lose their look.
     */
    private static final Set<String> KEPT_BY_GRAFANA = Set.of("display", "flex-wrap", "gap", "align-items",
            "padding", "border", "border-radius", "background-color", "color", "text-decoration", "font-weight");

    private record Row(String page, int chart, List<String> buttons) {
    }

    private static final List<Row> ROWS = List.of(
            new Row("02-candles-and-calls.json", 20, List.of("CAN SLIM", "Stock Trend", "Company Details")),
            new Row("04-stock-trend.json", 10, List.of("Candles and Calls", "CAN SLIM", "Company Details")),
            new Row("06-canslim.json", 20, List.of("Candles and Calls", "Stock Trend", "Company Details")));

    @Test
    @DisplayName("each stock page has one row of buttons, straight under its chart, across the page")
    void oneRowUnderTheChart() throws IOException {
        for (Row row : ROWS) {
            JsonNode dashboard = dashboard(row.page());
            List<JsonNode> rows = buttonRows(dashboard);
            assertEquals(1, rows.size(), row.page());
            JsonNode panel = rows.getFirst();
            JsonNode at = panel.path("gridPos");
            JsonNode chart = panel(dashboard, row.chart()).path("gridPos");

            assertEquals(bottom(chart), at.path("y").asInt(), row.page() + ": straight after the chart");
            assertEquals(0, at.path("x").asInt(), row.page());
            assertEquals(24, at.path("w").asInt(), row.page() + ": one row, across the page");
            assertEquals("html", panel.path("options").path("mode").asText(), row.page());
            assertEquals("", panel.path("title").asText(), row.page() + ": a title would take half of the row");
            assertTrue(panel.path("transparent").asBoolean(), row.page() + ": buttons on the page, not in a box");
            assertNoOverlap(row.page(), dashboard);
        }
    }

    @Test
    @DisplayName("the buttons are the pages asked for, in order, each opening on the symbol picked")
    void eachButtonOpensItsPageOnTheSymbol() throws IOException {
        Map<String, JsonNode> byTitle = new HashMap<>();
        try (var files = Files.list(DASHBOARDS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(file));
                byTitle.put(root.path("title").asText(), root);
            }
        }
        for (Row row : ROWS) {
            JsonNode dashboard = dashboard(row.page());
            Set<String> own = variableNames(dashboard);
            List<String> titles = new ArrayList<>();
            Matcher button = BUTTON.matcher(buttonRows(dashboard).getFirst().path("options").path("content").asText());
            while (button.find()) {
                String href = button.group(1);
                String title = button.group(2);
                titles.add(title);
                JsonNode target = byTitle.get(title);
                assertTrue(target != null, row.page() + ": no page called " + title);
                assertTrue(href.startsWith("/d/" + target.path("uid").asText() + "/"), title + ": " + href);

                Set<String> carried = new HashSet<>();
                for (String parameter : href.substring(href.indexOf('?') + 1).split("&")) {
                    Matcher variable = CARRIED.matcher(parameter);
                    assertTrue(variable.matches(),
                            title + ": only the page's own variables go along, never a fixed value or the time range: "
                                    + parameter);
                    carried.add(variable.group(1));
                }
                assertTrue(carried.contains("symbol"), title + " takes the symbol along: " + href);
                Set<String> shared = new HashSet<>(own);
                shared.retainAll(variableNames(target));
                shared.retainAll(Set.of("symbol", "detector"));
                assertEquals(shared, carried, title + ": the symbol, and the detector where both pages have the box");
            }
            assertEquals(row.buttons(), titles, row.page());
        }
    }

    @Test
    @DisplayName("Company Details moved from the top of Candles and Calls into the row; Yesterday's trading hours stays")
    void companyDetailsMovedIntoTheRow() throws IOException {
        List<String> top = new ArrayList<>();
        dashboard("02-candles-and-calls.json").path("links").forEach(link -> top.add(link.path("title").asText()));
        assertEquals(List.of("Yesterday's trading hours"), top);
    }

    @Test
    @DisplayName("Company Details links to the other stock pages at its top, each taking the symbol and not the time")
    void companyDetailsLinksBack() throws IOException {
        JsonNode dashboard = dashboard("07-company-details.json");
        assertEquals(Set.of("symbol"), variableNames(dashboard),
                "carrying the page's variables carries the symbol alone");
        Map<String, String> pages = Map.of("Candles and Calls", "stockanalyzer-candles",
                "Stock Trend", "stockanalyzer-trend", "CAN SLIM", "stockanalyzer-canslim");
        List<String> titles = new ArrayList<>();
        for (JsonNode link : dashboard.path("links")) {
            String title = link.path("title").asText();
            titles.add(title);
            assertTrue(link.path("url").asText().startsWith("/d/" + pages.get(title) + "/"), title);
            assertEquals("link", link.path("type").asText(), title);
            assertTrue(link.path("includeVars").asBoolean(), title + " takes the symbol along");
            assertFalse(link.path("keepTime").asBoolean(), title + " opens on its own range");
        }
        assertEquals(List.of("Candles and Calls", "Stock Trend", "CAN SLIM"), titles);
    }

    @Test
    @DisplayName("the buttons use only styles Grafana keeps")
    void onlyStylesGrafanaKeeps() throws IOException {
        for (Row row : ROWS) {
            Matcher style = STYLE.matcher(buttonRows(dashboard(row.page())).getFirst()
                    .path("options").path("content").asText());
            int checked = 0;
            while (style.find()) {
                for (String declaration : style.group(1).split(";")) {
                    if (declaration.isBlank()) {
                        continue;
                    }
                    String property = declaration.substring(0, declaration.indexOf(':')).trim();
                    assertTrue(KEPT_BY_GRAFANA.contains(property), row.page() + ": Grafana drops " + property);
                    checked++;
                }
            }
            assertTrue(checked > 10, row.page() + ": the row and its buttons are styled");
            assertFalse(buttonRows(dashboard(row.page())).getFirst().path("options").path("content").asText()
                    .contains("<script"), row.page());
        }
    }

    private static List<JsonNode> buttonRows(JsonNode dashboard) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            if (panel.path("type").asText().equals("text") && BUTTON.matcher(
                    panel.path("options").path("content").asText()).find()) {
                rows.add(panel);
            }
        }
        return rows;
    }

    private static void assertNoOverlap(String page, JsonNode dashboard) {
        List<JsonNode> panels = new ArrayList<>();
        dashboard.path("panels").forEach(panels::add);
        for (int a = 0; a < panels.size(); a++) {
            for (int b = a + 1; b < panels.size(); b++) {
                JsonNode p = panels.get(a).path("gridPos");
                JsonNode q = panels.get(b).path("gridPos");
                boolean apart = p.path("x").asInt() + p.path("w").asInt() <= q.path("x").asInt()
                        || q.path("x").asInt() + q.path("w").asInt() <= p.path("x").asInt()
                        || bottom(p) <= q.path("y").asInt()
                        || bottom(q) <= p.path("y").asInt();
                assertTrue(apart, page + ": panels " + panels.get(a).path("id") + " and "
                        + panels.get(b).path("id") + " overlap");
            }
        }
    }

    private static Set<String> variableNames(JsonNode dashboard) {
        Set<String> names = new HashSet<>();
        dashboard.path("templating").path("list").forEach(v -> names.add(v.path("name").asText()));
        return names;
    }

    private static JsonNode panel(JsonNode dashboard, int id) {
        for (JsonNode panel : dashboard.path("panels")) {
            if (panel.path("id").asInt() == id) {
                return panel;
            }
        }
        throw new AssertionError("no panel " + id);
    }

    private static int bottom(JsonNode gridPos) {
        return gridPos.path("y").asInt() + gridPos.path("h").asInt();
    }

    private static JsonNode dashboard(String page) throws IOException {
        return JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARDS.resolve(page)));
    }
}
