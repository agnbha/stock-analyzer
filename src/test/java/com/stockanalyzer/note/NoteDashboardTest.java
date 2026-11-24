package com.stockanalyzer.note;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockanalyzer.util.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteDashboardTest {

    @Test
    @DisplayName("short names and uids, in any case and order, without repeats")
    void parsesNames() {
        assertEquals(List.of(NoteDashboard.CANDLES, NoteDashboard.CANSLIM, NoteDashboard.TREND),
                NoteDashboard.parseList("candles, CANSLIM,stockanalyzer-trend,candles"));
    }

    @Test
    @DisplayName("all is every chart, which is no limit")
    void allIsNoLimit() {
        assertEquals(List.of(), NoteDashboard.parseList(" All "));
    }

    @Test
    @DisplayName("an unknown name is refused with the names there are; so is an empty list")
    void refusesUnknownNames() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> NoteDashboard.parseList("candles,charts"));
        assertTrue(unknown.getMessage().contains("'charts'") && unknown.getMessage().contains("canslim")
                && unknown.getMessage().contains("or all"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> NoteDashboard.parseList(" , "));
    }

    @Test
    @DisplayName("the dashboards named are exactly the ones with a Saved notes layer, with their titles")
    void matchesTheDashboards() throws IOException {
        Map<String, String> withLayer = new TreeMap<>();
        try (Stream<Path> files = Files.list(Path.of("grafana", "dashboards"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                JsonNode root = JsonMapper.INSTANCE.readTree(Files.readString(file));
                boolean saved = StreamSupport.stream(root.path("annotations").path("list").spliterator(), false)
                        .anyMatch(a -> a.path("name").asText().equals("Saved notes"));
                if (saved) {
                    withLayer.put(root.path("uid").asText(), root.path("title").asText());
                }
            }
        }
        Map<String, String> named = Arrays.stream(NoteDashboard.values())
                .collect(Collectors.toMap(NoteDashboard::uid, NoteDashboard::title, (a, b) -> a, TreeMap::new));
        assertEquals(withLayer, named);
        for (NoteDashboard dashboard : NoteDashboard.values()) {
            assertEquals("stockanalyzer-" + dashboard.shortName(), dashboard.uid());
        }
    }
}
