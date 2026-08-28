package com.stockanalyzer.client;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.StockCandleSeries;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Decorator that turns one long-range request into several provider-legal ones
 * and stitches the results back together, de-duplicating the candles that
 * overlapping chunk boundaries can return twice.
 */
public final class ChunkedCandleDataClient implements CandleDataClient {

    private final CandleDataClient delegate;
    private final CandleRangeChunker chunker;

    public ChunkedCandleDataClient(CandleDataClient delegate, CandleRangeChunker chunker) {
        this.delegate = delegate;
        this.chunker = chunker;
    }

    @Override
    public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                           LocalDateTime startTime, LocalDateTime endTime, int intervalMinutes) {
        List<CandleRangeChunker.Range> ranges = chunker.chunk(startTime, endTime);
        if (ranges.size() == 1) {
            return delegate.fetchCandles(symbol, exchange, segment, startTime, endTime, intervalMinutes);
        }

        TreeMap<Long, Candle> merged = new TreeMap<>();
        for (CandleRangeChunker.Range range : ranges) {
            StockCandleSeries part = delegate.fetchCandles(symbol, exchange, segment,
                    range.from(), range.to(), intervalMinutes);
            for (Candle candle : part.candles()) {
                merged.putIfAbsent(candle.epochSeconds(), candle);
            }
        }
        return new StockCandleSeries(symbol, exchange, segment, new ArrayList<>(merged.values()));
    }
}
