package com.stockanalyzer.store;

import com.stockanalyzer.canslim.CanSlimScore;
import com.stockanalyzer.canslim.MarketAssessment;

import java.util.Map;

/** Each CAN SLIM scan: the market reading it made and every stock it scored. */
public interface CanSlimRepository {

    /**
     * Records one scan in a single transaction and returns its id.
     *
     * @param scores keyed by instrument id
     */
    long saveRun(long runAtEpoch, String query, int screened, MarketAssessment market,
                 Map<Long, CanSlimScore> scores);

    /**
     * Drops the whole-market scores of all but the newest {@code keepRuns} scans that had any. A market
     * scan stores a row for every ranked listing - thousands a night - which the history of a
     * signal does not need; the scores of holdings and of screened companies are never pruned.
     */
    void pruneMarketScores(int keepRuns);
}
