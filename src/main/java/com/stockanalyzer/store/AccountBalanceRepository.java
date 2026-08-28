package com.stockanalyzer.store;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The cash in the account, as recorded by you or pulled from the broker.
 *
 * <p>Without this, an equity curve is only cumulative P&L - a different claim
 * from "this is what the account is worth". The dashboards show which of the
 * two they are drawing.
 */
public interface AccountBalanceRepository {

    void record(LocalDate sessionDate, double cash, Double invested, String source);

    Optional<Balance> latestOnOrBefore(LocalDate date);

    List<Balance> findBetween(LocalDate from, LocalDate to);

    record Balance(LocalDate sessionDate, double cash, Double invested, Double total, String source) {
    }
}
