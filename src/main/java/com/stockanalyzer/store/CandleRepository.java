package com.stockanalyzer.store;

import com.stockanalyzer.model.Candle;

import java.util.List;

public interface CandleRepository {

    /** Inserts candles, ignoring ones already stored. Returns the number newly written. */
    int saveAll(long instrumentId, int intervalMinutes, List<Candle> candles);

    List<Candle> find(long instrumentId, int intervalMinutes, long fromTsInclusive, long toTsInclusive);
}
