package com.stockanalyzer.store;

import com.stockanalyzer.model.ChartNote;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The analyzer's copy of the notes written on the charts; see {@code chartnotes sync}. */
public interface ChartNoteRepository {

    /**
     * Writes the notes, replacing what Grafana says about ones already stored. A symbol that was assigned by
     * hand is kept, and a note that had been marked deleted comes back if Grafana has it again.
     *
     * @return how many were new
     */
    int upsert(List<ChartNote> notes);

    /** Marks as deleted the stored notes whose ids are not in {@code present}. Returns how many were newly marked. */
    int markMissing(Set<Long> present, long nowEpoch);

    /** Gives a note a symbol by hand. Returns false when there is no such note. */
    boolean assignSymbol(long grafanaId, String symbol);

    /** Notes that are still in Grafana, oldest first; {@code symbolOrNull} narrows to one symbol. */
    List<ChartNote> findLive(String symbolOrNull);

    /**
     * Draws the notes given {@code symbol} by hand only on these dashboards (their uids); an empty list draws them on
     * every chart again.
     */
    void limitTo(String symbol, List<String> dashboardUids, long nowEpoch);

    /** The symbols whose notes are drawn on some dashboards only, by symbol, with those dashboards' uids. */
    Map<String, List<String>> limits();
}
