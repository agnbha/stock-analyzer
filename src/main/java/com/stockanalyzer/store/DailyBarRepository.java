package com.stockanalyzer.store;

import com.stockanalyzer.canslim.DailyBar;

import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Daily-interval bars, kept apart from {@code trading_day}: those sessions are
 * built from the intraday tape for the configured universe, while these come
 * straight from the provider's daily candles for any symbol a scan prices.
 */
public interface DailyBarRepository {

    /** Inserts or refreshes each bar; {@code tsEpoch} places the bar on a time axis. */
    void upsert(long instrumentId, List<DailyBar> bars, ToLongFunction<DailyBar> tsEpoch);

    List<DailyBar> find(long instrumentId);
}
