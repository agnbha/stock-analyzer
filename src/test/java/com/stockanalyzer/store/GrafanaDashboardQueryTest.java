package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.util.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every query in every provisioned dashboard is run against a real, migrated
 * database. A typo in dashboard SQL should fail the build, not turn into an
 * empty panel nobody investigates.
 */
class GrafanaDashboardQueryTest {

    private static final Path DASHBOARDS = Path.of("grafana", "dashboards");
    private static final long FROM_EPOCH_SECONDS = 1787000000L;
    private static final long TO_EPOCH_SECONDS = 1788300000L;

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
    @DisplayName("every dashboard panel query is valid SQL against the real schema")
    void everyPanelQueryRuns() throws Exception {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (Path file : dashboardFiles()) {
            JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(file));
            for (String sql : queriesIn(root)) {
                checked++;
                // Template variables are substituted by Grafana before the query
                // runs. Substitute all of them, or a query referencing an unknown
                // one still parses and silently proves nothing.
                String executable = sql
                        // Multi-value variables are interpolated by Grafana with a
                        // format suffix; the SQL form yields a quoted list.
                        .replace("${session:sqlstring}", "'2026-08-27','2026-08-28'")
                        .replace("$__from/1000", String.valueOf(FROM_EPOCH_SECONDS))
                        .replace("$__to/1000", String.valueOf(TO_EPOCH_SECONDS))
                        .replace("$symbol", "RELIANCE")
                        .replace("$session", "2026-08-27")
                        .replace("$detector", "topk-nonoverlap/highlow/v1");
                assertFalse(executable.contains("$"),
                        "unsubstituted variable in " + file.getFileName() + ": " + sql);
                try {
                    database.read(connection -> {
                        try (Statement statement = connection.createStatement();
                             ResultSet rs = statement.executeQuery(executable)) {
                            rs.next();
                            return null;
                        }
                    });
                } catch (Exception e) {
                    failures.add(file.getFileName() + ": " + e.getMessage()
                            + System.lineSeparator() + sql);
                }
            }
        }

        assertTrue(checked >= 15, "expected to find the dashboard queries, found " + checked);
        if (!failures.isEmpty()) {
            fail("Dashboard queries failed:" + System.lineSeparator()
                    + String.join(System.lineSeparator() + System.lineSeparator(), failures));
        }
    }

    @Test
    @DisplayName("dashboards point at the provisioned datasource")
    void dashboardsUseTheProvisionedDatasource() throws Exception {
        for (Path file : dashboardFiles()) {
            String content = Files.readString(file);
            assertTrue(content.contains("stock-analyzer-sqlite"),
                    file.getFileName() + " must reference the provisioned datasource uid");
            assertFalse(content.contains("\"datasource\": null"),
                    file.getFileName() + " has a panel with no datasource");
        }
    }

    @Test
    @DisplayName("template variable queries are plain strings, not query objects")
    void variableQueriesAreStrings() throws Exception {
        // The datasource's metricFindQuery assigns this value straight into its
        // queryText field, which Go declares as a string. An object there fails
        // with "cannot unmarshal object into Go struct field
        // queryModel.queryText of type string" and the dropdown stays empty -
        // while every panel still works, because panels read rawQueryText.
        for (Path file : dashboardFiles()) {
            JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(file));
            for (JsonNode variable : root.path("templating").path("list")) {
                if (!"query".equals(variable.path("type").asText())) {
                    continue;
                }
                assertTrue(variable.path("query").isTextual(),
                        file.getFileName() + ": variable '" + variable.path("name").asText()
                                + "' must define its query as a string, was "
                                + variable.path("query").getNodeType());
            }
        }
    }

    private static List<Path> dashboardFiles() throws Exception {
        assertTrue(Files.isDirectory(DASHBOARDS), "grafana/dashboards is missing");
        try (Stream<Path> files = Files.list(DASHBOARDS)) {
            List<Path> json = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
            assertFalse(json.isEmpty(), "no dashboards found");
            return json;
        }
    }

    /** Panel targets plus any template variable that queries the database. */
    private static List<String> queriesIn(JsonNode root) {
        List<String> queries = new ArrayList<>();
        for (JsonNode panel : root.withArray("panels")) {
            for (JsonNode target : panel.withArray("targets")) {
                if (target.hasNonNull("rawQueryText")) {
                    queries.add(target.get("rawQueryText").asText());
                }
            }
        }
        for (JsonNode variable : root.path("templating").path("list")) {
            JsonNode query = variable.path("query");
            if (query.isTextual()) {
                queries.add(query.asText());
            } else if (query.hasNonNull("rawQueryText")) {
                queries.add(query.get("rawQueryText").asText());
            }
        }
        return queries;
    }
}
