package com.stockanalyzer.note;

import com.stockanalyzer.model.ChartNote;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.store.TestDatabase;
import com.stockanalyzer.store.jdbc.SqliteChartNoteRepository;
import com.stockanalyzer.store.jdbc.SqliteInstrumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartNoteSyncTest {

    @TempDir
    Path directory;

    private Database database;
    private SqliteChartNoteRepository repository;
    private final List<GrafanaAnnotation> grafana = new ArrayList<>();
    private ChartNoteSync sync;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(directory);
        repository = new SqliteChartNoteRepository(database);
        SqliteInstrumentRepository instruments = new SqliteInstrumentRepository(database);
        instruments.findOrCreate("BHARTIARTL", "NSE", "CASH");
        instruments.findOrCreate("WIPRO", "NSE", "CASH");
        sync = new ChartNoteSync(() -> List.copyOf(grafana), repository, instruments, SentimentVocabulary.defaults());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private static GrafanaAnnotation note(long id, long epochSeconds, String text, String... tags) {
        return new GrafanaAnnotation(id, "stockanalyzer-candles", 20, epochSeconds * 1000, epochSeconds * 1000, text,
                List.of(tags));
    }

    private ChartNote stored(long id) {
        return repository.findLive(null).stream().filter(n -> n.grafanaId() == id).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("your own UP and LEVEL notes are kept with their generic sentiment, and the tags as you wrote them")
    void ownLabelsBecomeGenericSentiments() {
        grafana.add(note(1, 1000, "Lower than last close. Should go up", "UP"));
        grafana.add(note(2, 2000, "Expecting less return", "LEVEL"));

        ChartNoteSync.Result result = sync.sync(9_000);

        assertEquals(2, result.found());
        assertEquals(2, result.added());
        assertEquals("BULLISH", stored(1).sentiment());
        assertEquals("NEUTRAL", stored(2).sentiment());
        assertEquals(List.of("UP"), stored(1).tags(), "Grafana's own tags are not rewritten");
        assertEquals("Lower than last close. Should go up", stored(1).note());
        assertEquals(1000L, stored(1).notedAt());
    }

    @Test
    @DisplayName("a tag that names an instrument ties the note to it; a note with none has no symbol")
    void symbolComesFromATag() {
        grafana.add(note(1, 1000, "tagged", "wipro", "BULLISH"));
        grafana.add(note(2, 2000, "stray", "UP"));

        ChartNoteSync.Result result = sync.sync(9_000);

        assertEquals("WIPRO", stored(1).symbol());
        assertEquals("TAG", stored(1).symbolSource());
        assertNull(stored(2).symbol());
        assertEquals(1, result.withSymbol());
    }

    @Test
    @DisplayName("syncing again adds nothing, and an edit in Grafana is picked up")
    void repeatable() {
        grafana.add(note(1, 1000, "first thought", "UP"));
        sync.sync(9_000);

        assertEquals(0, sync.sync(9_100).added());

        grafana.set(0, note(1, 1000, "second thought", "DOWN"));
        sync.sync(9_200);
        assertEquals(1, repository.findLive(null).size());
        assertEquals("second thought", stored(1).note());
        assertEquals("BEARISH", stored(1).sentiment());
    }

    @Test
    @DisplayName("a symbol given by hand survives a later sync")
    void manualSymbolSurvives() {
        grafana.add(note(1, 1000, "stray", "UP"));
        sync.sync(9_000);
        assertTrue(repository.assignSymbol(1, "BHARTIARTL"));

        sync.sync(9_100);

        assertEquals("BHARTIARTL", stored(1).symbol());
        assertEquals("MANUAL", stored(1).symbolSource());
    }

    @Test
    @DisplayName("a note deleted in Grafana is marked, not lost, and comes back if Grafana has it again")
    void deletionIsMarked() {
        grafana.add(note(1, 1000, "kept", "UP"));
        grafana.add(note(2, 2000, "to delete", "LEVEL"));
        sync.sync(9_000);

        grafana.remove(1);
        ChartNoteSync.Result result = sync.sync(9_100);

        assertEquals(1, result.removed());
        assertEquals(1, repository.findLive(null).size(), "the deleted one no longer counts as live");

        grafana.add(note(2, 2000, "to delete", "LEVEL"));
        sync.sync(9_200);
        assertEquals(2, repository.findLive(null).size());
    }

    @Test
    @DisplayName("assigning a symbol to a note that is not there says so")
    void assigningToNothing() {
        assertEquals(false, repository.assignSymbol(99, "WIPRO"));
    }

    @Test
    @DisplayName("an unreadable Grafana database is reported, not swallowed")
    void unavailableSource() {
        ChartNoteSync broken = new ChartNoteSync(() -> {
            throw new GrafanaAnnotationSource.Unavailable("no database");
        }, repository, new SqliteInstrumentRepository(database), SentimentVocabulary.defaults());

        assertThrows(GrafanaAnnotationSource.Unavailable.class, () -> broken.sync(1));
    }
}
