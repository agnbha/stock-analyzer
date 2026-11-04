package com.stockanalyzer.note;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading notes out of a file shaped like Grafana's own database. */
class GrafanaDbAnnotationSourceTest {

    @TempDir
    Path directory;

    private Path grafanaDb() throws Exception {
        Path file = directory.resolve("grafana.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE dashboard (id INTEGER PRIMARY KEY, uid TEXT)");
            s.execute("""
                    CREATE TABLE annotation (id INTEGER PRIMARY KEY, alert_id INTEGER, dashboard_id INTEGER, panel_id INTEGER,
                                             type TEXT, text TEXT, epoch INTEGER, epoch_end INTEGER, tags TEXT)""");
            s.execute("INSERT INTO dashboard VALUES (3, 'stockanalyzer-candles')");
            s.execute("INSERT INTO annotation VALUES (1, 0, 3, 20, '', 'Lower than last close. Should go up', 1793764438000, 1793764438000, '[\"UP\"]')");
            s.execute("INSERT INTO annotation VALUES (2, 0, 3, 20, '', 'Wrong buy', 1793764058000, 1793764058000, '[\"LEVEL\",\"BHARTIARTL\"]')");
            s.execute("INSERT INTO annotation VALUES (3, 7, 3, 20, 'alert', 'An alert firing', 1793764438000, 1793764438000, '[]')");
            s.execute("INSERT INTO annotation VALUES (4, 0, 0, NULL, '', 'An organisation note', 1793764438000, 0, NULL)");
            s.execute("INSERT INTO annotation VALUES (5, 0, 3, NULL, '', 'A dashboard note with no panel', 1793764438000, 0, '[]')");
        }
        return file;
    }

    @Test
    @DisplayName("notes people wrote are read with their dashboard, panel, time and tags; alert annotations are not")
    void readsNotes() throws Exception {
        List<GrafanaAnnotation> notes = new GrafanaDbAnnotationSource(grafanaDb()).fetch();

        assertEquals(List.of(1L, 2L, 4L, 5L), notes.stream().map(GrafanaAnnotation::id).toList());
        GrafanaAnnotation first = notes.getFirst();
        assertEquals("stockanalyzer-candles", first.dashboardUid());
        assertEquals(20, first.panelId());
        assertEquals(1793764438000L, first.epochMs());
        assertEquals(List.of("UP"), first.tags());
        assertEquals(List.of("LEVEL", "BHARTIARTL"), notes.get(1).tags());
        assertEquals(List.of(), notes.get(2).tags(), "a note with no tags");
        assertEquals(notes.get(2).epochMs(), notes.get(2).epochEndMs(), "a point note ends where it starts");
        assertEquals(null, notes.get(2).panelId(), "an organisation note belongs to no panel");
        assertEquals(null, notes.get(2).dashboardUid());
        assertEquals("stockanalyzer-candles", notes.get(3).dashboardUid(), "a note's dashboard survives it having no panel");
        assertEquals(null, notes.get(3).panelId());
    }

    @Test
    @DisplayName("the original file is left alone and no copy is left behind")
    void leavesNoTrace() throws Exception {
        Path file = grafanaDb();
        long before = file.toFile().length();

        new GrafanaDbAnnotationSource(file).fetch();

        assertEquals(before, file.toFile().length());
        try (var listing = java.nio.file.Files.list(directory)) {
            assertEquals(List.of("grafana.db"), listing.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    @DisplayName("a database that is not there is reported as unavailable, with where it looked")
    void missing() {
        GrafanaAnnotationSource.Unavailable failure = assertThrows(GrafanaAnnotationSource.Unavailable.class,
                () -> new GrafanaDbAnnotationSource(directory.resolve("none.db")).fetch());

        assertTrue(failure.getMessage().contains("none.db") && failure.getMessage().contains("grafana.db.path"),
                failure.getMessage());
    }
}
