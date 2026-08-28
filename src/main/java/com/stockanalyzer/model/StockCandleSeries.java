package com.stockanalyzer.model;

import java.util.List;

/** A time-ordered list of candles for one instrument. */
public record StockCandleSeries(String symbol, String exchange, String segment, List<Candle> candles) {
}
