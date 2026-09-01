package com.stockanalyzer.store;

import com.stockanalyzer.model.Candle;

import java.time.LocalDate;
import java.util.List;

/**
 * Staging for a session in progress.
 *
 * <p>Kept apart from {@link CandleRepository} on purpose: rows here may still be
 * forming, and the canonical tables only ever take settled data.
 */
public interface LiveCandleRepository {

    /**
     * Writes what the monitor has seen so far, overwriting earlier versions of
     * the same minute - which is how a forming candle settles.
     *
     * @param provisionalFrom timestamp of the first candle that is still
     *                        forming, or 0 when all of them have closed
     */
    void upsertAll(long instrumentId, LocalDate sessionDate, int intervalMinutes,
                   List<Candle> candles, long provisionalFrom);

    /** Drops staged rows for sessions whose authoritative tape is now stored. */
    int deleteConsolidated();

    int countForSession(LocalDate sessionDate);
}
