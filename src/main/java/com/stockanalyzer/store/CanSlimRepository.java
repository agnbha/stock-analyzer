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
}
