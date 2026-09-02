package com.stockanalyzer.store;

import com.stockanalyzer.model.AccountSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * What the account holds, as read from the broker (or, failing that, as typed in).
 *
 * <p>Without this, an equity curve is only cumulative P&amp;L - a different claim
 * from "this is what the account is worth". The dashboards show which of the two
 * they are drawing, and {@code source} on every row is what lets them.
 */
public interface AccountBalanceRepository {

    /** Records a balance entered by hand. {@code source} is the provenance, e.g. {@code manual}. */
    void record(LocalDate sessionDate, double cash, Double invested, String source);

    /**
     * Records a reading taken from the broker, holdings included. Replaces any
     * earlier row for the day, so re-running is safe and the last read wins.
     */
    void record(LocalDate sessionDate, AccountSnapshot snapshot);

    Optional<Balance> latestOnOrBefore(LocalDate date);

    List<Balance> findBetween(LocalDate from, LocalDate to);

    /**
     * @param marginUsed        cash blocked against positions; null for manual entries
     * @param available         cash free to deploy; null for manual entries
     * @param unpricedHoldings  holdings valued at cost because no quote came back
     */
    record Balance(LocalDate sessionDate, double cash, Double invested, Double total, String source,
                   Double marginUsed, Double available, Integer unpricedHoldings) {

        /** True when the broker was the source, rather than a number typed in. */
        public boolean fromBroker() {
            return "broker".equals(source);
        }
    }
}
