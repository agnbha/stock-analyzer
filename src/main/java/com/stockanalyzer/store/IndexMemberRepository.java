package com.stockanalyzer.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The members of the indices Market Watch follows; see {@code CanSlimMain marketwatch}. */
public interface IndexMemberRepository {

    /** Replaces an index's members, read from {@code source}, and puts the index at {@code position} in the page's order. */
    void replace(String index, int position, List<String> symbols, String exchange, String source, long nowEpoch);

    /** Puts an index at {@code position} in the page's order, keeping the members it holds. */
    void reorder(String index, int position);

    /** Drops the indices not named. Returns how many were dropped. */
    int retainOnly(Collection<String> indices);

    /** How many members each index holds, by index. */
    Map<String, Integer> counts();

    /** Every symbol in any index, once, with the first and last session it has a daily bar for (null when none). */
    List<BarSpan> barSpans(String segment);

    /** The latest session any stock has a daily bar for. */
    Optional<LocalDate> latestSession();

    record BarSpan(String symbol, String exchange, LocalDate first, LocalDate last) {
    }
}
