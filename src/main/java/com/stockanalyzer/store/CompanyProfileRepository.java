package com.stockanalyzer.store;

import com.stockanalyzer.canslim.CompanyProfile;

import java.util.Optional;

/** The company profiles read from screener.in, one per instrument, for the dashboards. */
public interface CompanyProfileRepository {

    /** Replaces everything stored for the company with this reading, quarters and shareholding included. */
    void save(long instrumentId, CompanyProfile profile, long fetchedAtEpoch);

    /** Records that the page was read and had nothing on the company, so it is not asked again until stale. */
    void saveEmpty(long instrumentId, long fetchedAtEpoch);

    /** When the company's page was last read, if ever. */
    Optional<Long> fetchedAt(long instrumentId);
}
