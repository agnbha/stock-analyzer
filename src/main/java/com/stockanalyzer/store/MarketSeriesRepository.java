package com.stockanalyzer.store;

import com.stockanalyzer.markets.Observation;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Daily market series read from outside the broker (US Treasury yields, crude oil), by the source's series id. */
public interface MarketSeriesRepository {

    /** Inserts each reading, or replaces the one held for that day; {@code fetchedAtEpoch} records when. */
    void upsert(String series, List<Observation> observations, long fetchedAtEpoch);

    /** The date of the newest reading held for the series, if any. */
    Optional<LocalDate> latest(String series);
}
