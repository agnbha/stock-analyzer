package com.stockanalyzer.store;

import com.stockanalyzer.canslim.CompanyFundamentals;

import java.util.Optional;

/**
 * Fundamentals fetched from screener.in company pages, with the quarters they showed,
 * kept until the calendar says a company may have reported again.
 */
public interface FundamentalsCacheRepository {

    record Cached(long fetchedAtEpoch, CompanyFundamentals fundamentals) {
    }

    Optional<Cached> find(long instrumentId);

    void upsert(long instrumentId, long fetchedAtEpoch, CompanyFundamentals fundamentals);
}
