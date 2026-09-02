package com.stockanalyzer.store;

import com.stockanalyzer.model.GainOpportunity;

import java.time.LocalDate;
import java.util.List;

/**
 * The windows found so far in a session in progress.
 *
 * <p>Recomputed from scratch on every tick, because a later minute can change
 * which three windows are best - so this replaces rather than accumulates.
 */
public interface LiveOpportunityRepository {

    void replace(long instrumentId, LocalDate sessionDate, String detectorVersion,
                 List<GainOpportunity> opportunities);

    /** Clears staged windows for a session whose authoritative version is now stored. */
    int deleteForSession(LocalDate sessionDate);

    int countForSession(LocalDate sessionDate);
}
