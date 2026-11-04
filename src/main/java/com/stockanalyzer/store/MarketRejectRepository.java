package com.stockanalyzer.store;

import java.util.List;

/**
 * Listings the broker will not serve candles for, remembered so a market-wide scan does not spend a
 * request on each of them every night. The instrument list carries symbols that the candle API
 * refuses or has no history for; nothing in the list says which.
 */
public interface MarketRejectRepository {

    /**
     * @param reason          {@code GA001} when Groww did not recognise the symbol, {@code NO_CANDLES} when it
     *                        answered with no daily bars
     * @param firstRejectedAt epoch seconds of the first refusal
     * @param lastRejectedAt  epoch seconds of the latest one: the memory is counted from here
     * @param times           how many scans have been refused it
     */
    record Reject(String symbol, String exchange, String reason, long firstRejectedAt, long lastRejectedAt,
                  int times) {
    }

    List<Reject> findAll();

    /** Notes a refusal at {@code atEpoch}: the first one adds the row, a repeat renews it. */
    void record(String symbol, String exchange, String reason, long atEpoch);

    /** Forgets a listing, because it was served again. */
    void clear(String symbol, String exchange);
}
