package com.stockanalyzer.model;

import java.util.List;

/**
 * What the monitor knows about one symbol right now. The last candle may be
 * {@code provisional} - still forming, so its high, low and close will change.
 * Provisional candles never reach the canonical tables.
 */
public record LiveSymbolState(String symbol,
                              List<Candle> candles,
                              boolean lastCandleProvisional,
                              double lastPrice,
                              Double dayChangePct,
                              double volumeRatio,
                              List<GainOpportunity> topSoFar,
                              List<MarketEvent> eventsToday,
                              SignalPrediction latestSignal,
                              Double projectedReturnPct,
                              long lastUpdatedEpoch) {
}
