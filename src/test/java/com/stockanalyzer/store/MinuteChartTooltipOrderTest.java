package com.stockanalyzer.store;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Grafana lists the series in a candlestick tooltip in the order the query returns its columns, so the
 * order of the minute-candle panel's select list is the order you read the tooltip in.
 */
class MinuteChartTooltipOrderTest {

    private static final Path DASHBOARD = Path.of("grafana", "dashboards", "02-candles-and-calls.json");

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
    @DisplayName("the tooltip reads open, close, high, low, volume, VWAP, the three EMAs, previous close, last day's high and low, the CPR levels, tomorrow's central pivots, then your marks")
    void tooltipOrder() throws IOException {
        JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(DASHBOARD));
        JsonNode panel = StreamSupport.stream(root.path("panels").spliterator(), false)
                .filter(p -> p.path("id").asInt() == 20).findFirst().orElseThrow();
        String sql = panel.path("targets").get(0).path("queryText").asText()
                .replace("${session:sqlstring}", "'2026-11-04'")
                .replace("$candle_size", "1")
                .replace("${profile:sqlstring}", "'" + AccountProfileRepository.DEFAULT_PROFILE + "'")
                .replace("$symbol", "WIPRO")
                .replace("${levels:csv}", "r1,r2,r3,pivots,predicted,hl,prevclose,ema10,ema35,ema60,vwap")
                .replace("$detector", "topk-nonoverlap/highlow/v1");

        List<String> columns = database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> labels = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    labels.add(meta.getColumnLabel(i));
                }
                return labels;
            }
        });

        assertEquals(List.of("time", "open", "close", "high", "low", "volume", "VWAP", "EMA 10", "EMA 35", "EMA 60",
                "Previous close", "Last day high", "Last day low", "R3", "R2", "R1", "TC", "Pivot", "BC", "S1", "S2", "S3",
                "Next day TC", "Next day Pivot", "Next day BC", "Window entry", "Window exit", "Your buy", "Your sell"), columns);
    }
}
