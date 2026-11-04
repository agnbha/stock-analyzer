package com.stockanalyzer.store;

import com.stockanalyzer.model.Side;

import java.time.LocalDate;
import java.util.List;

/**
 * What you thought when you took a trade: a sentiment label (BULLISH, BEARISH, NERVOUS...) and
 * free text. The same note is kept against every fill of a call, because a call is usually one
 * order that arrives as many fills.
 */
public interface TradeNoteRepository {

    /** A noted fill, with enough of the trade to list it without another query. */
    record Noted(long tradeId, String symbol, Side side, LocalDate sessionDate, long executedTs, int quantity,
                 double price, String sentiment, String note) {
    }

    /** Writes the note against each fill, replacing one already there. */
    void upsertAll(List<Long> tradeIds, String sentiment, String note, long notedAtEpoch);

    /** Removes the notes from these fills; fills with none are ignored. Returns how many were removed. */
    int deleteAll(List<Long> tradeIds);

    /** Noted fills in the date range, oldest first; {@code symbolOrNull} narrows to one symbol. */
    List<Noted> findBetween(LocalDate from, LocalDate to, String symbolOrNull);
}
