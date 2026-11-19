package com.stockanalyzer.store;

import com.stockanalyzer.markets.Observation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Daily market series (US Treasury yields, crude oil, gold and silver; FPI, FII and DII flows and positions), by
 * series id. */
public interface MarketSeriesRepository {

    /** Inserts each reading, or replaces the one held for that day; {@code fetchedAtEpoch} records when. */
    void upsert(String series, List<Observation> observations, long fetchedAtEpoch);

    /** The date of the newest reading held for the series, if any. */
    Optional<LocalDate> latest(String series);
}
