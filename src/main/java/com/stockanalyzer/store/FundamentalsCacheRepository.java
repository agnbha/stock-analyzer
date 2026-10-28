package com.stockanalyzer.store;

import java.util.Map;
import java.util.Optional;

/** Fundamentals fetched from screener.in company pages, kept until they are old enough to refetch. */
public interface FundamentalsCacheRepository {

    record Cached(long fetchedAtEpoch, Map<String, Double> ratios) {
    }

    Optional<Cached> find(long instrumentId);

    void upsert(long instrumentId, long fetchedAtEpoch, Map<String, Double> ratios);
}
