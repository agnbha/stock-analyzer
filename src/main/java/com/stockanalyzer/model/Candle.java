package com.stockanalyzer.model;

/**
 * A single OHLCV candle as returned by the Groww historical data API:
 * [epoch_seconds, open, high, low, close, volume].
 */
public record Candle(long epochSeconds, double open, double high, double low, double close, long volume) {
}
