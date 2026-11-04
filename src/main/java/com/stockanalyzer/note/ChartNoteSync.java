package com.stockanalyzer.note;

import com.stockanalyzer.model.ChartNote;
import com.stockanalyzer.model.Instrument;
import com.stockanalyzer.store.ChartNoteRepository;
import com.stockanalyzer.store.InstrumentRepository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies the notes written on the charts into the analyzer's database, so they are kept with the journal, can be
 * listed and joined to trades, and outlive Grafana's volume.
 *
 * <p>Safe to run again and again: a note is keyed by Grafana's annotation id, so a repeat updates what changed, a
 * note deleted in Grafana is marked rather than lost, and a symbol assigned by hand is not overwritten.
 */
public final class ChartNoteSync {

    private final GrafanaAnnotationSource source;
    private final ChartNoteRepository notes;
    private final InstrumentRepository instruments;
    private final SentimentVocabulary vocabulary;

    public ChartNoteSync(GrafanaAnnotationSource source, ChartNoteRepository notes, InstrumentRepository instruments,
                         SentimentVocabulary vocabulary) {
        this.source = source;
        this.notes = notes;
        this.instruments = instruments;
        this.vocabulary = vocabulary;
    }

    /**
     * @param found      notes in Grafana
     * @param added      of those, how many were not in the journal yet
     * @param withSymbol of those, how many a tag ties to a symbol
     * @param removed    notes no longer in Grafana, marked as deleted
     */
    public record Result(int found, int added, int withSymbol, int removed) {
    }

    /** @throws GrafanaAnnotationSource.Unavailable when Grafana's database cannot be read */
    public Result sync(long nowEpoch) {
        List<GrafanaAnnotation> annotations = source.fetch();
        Map<String, String> symbols = new HashMap<>();
        for (Instrument instrument : instruments.findAll()) {
            symbols.putIfAbsent(instrument.symbol().toUpperCase(Locale.ROOT), instrument.symbol());
        }

        List<ChartNote> converted = annotations.stream().map(a -> convert(a, symbols, nowEpoch)).toList();
        int added = notes.upsert(converted);
        Set<Long> present = new HashSet<>();
        annotations.forEach(a -> present.add(a.id()));
        int removed = notes.markMissing(present, nowEpoch);
        int withSymbol = (int) converted.stream().filter(n -> n.symbol() != null).count();
        return new Result(converted.size(), added, withSymbol, removed);
    }

    private ChartNote convert(GrafanaAnnotation a, Map<String, String> symbols, long nowEpoch) {
        String symbol = null;
        for (String tag : a.tags()) {
            symbol = symbols.get(tag.trim().toUpperCase(Locale.ROOT));
            if (symbol != null) {
                break;
            }
        }
        long at = a.epochMs() / 1000;
        long to = a.epochEndMs() / 1000;
        String text = a.text() == null || a.text().isBlank() ? null : a.text().strip();
        return new ChartNote(a.id(), a.dashboardUid(), a.panelId(), symbol, symbol == null ? null : "TAG", at,
                to == at ? null : to, vocabulary.fromTags(a.tags()), a.tags(), text, nowEpoch, null);
    }
}
