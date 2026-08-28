package com.stockanalyzer.store;

import com.stockanalyzer.model.HotWindow;

import java.util.List;

public interface HotWindowRepository {

    /** Replaces every window computed for this bucket size and lookback. */
    void replaceAll(int bucketMinutes, int lookbackDays, List<HotWindow> windows);

    /** Windows for one symbol (plus market-wide ones) ordered by descending lower bound. */
    List<HotWindow> find(String symbol, int bucketMinutes, int lookbackDays);

    List<HotWindow> findAll(int bucketMinutes, int lookbackDays);
}
