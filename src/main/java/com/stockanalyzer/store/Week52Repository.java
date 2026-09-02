package com.stockanalyzer.store;

import java.time.LocalDate;

/**
 * The 52-week range, kept apart from {@code trading_day} because it is derived
 * from daily-interval candles over a year rather than from the intraday tape.
 */
public interface Week52Repository {

    void upsert(long instrumentId, double high, double low, int sessions,
                LocalDate from, LocalDate to);

    int count();
}
