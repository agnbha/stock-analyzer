package com.stockanalyzer.model;

/**
 * A fundamentals/quote snapshot for one instrument. Populated by either
 * {@link com.stockanalyzer.client.GrowwFundamentalsClient} (Groww's live
 * quote endpoint, {@code GET /v1/live-data/quote} — requires the "Live
 * Data" entitlement) or {@link com.stockanalyzer.client.HistoricalFundamentalsClient}
 * (derived from historical daily candles, which needs only the standard
 * historical data access every key has).
 *
 * <p>{@code marketCap}, {@code upperCircuitLimit} and {@code lowerCircuitLimit}
 * are only ever available from the live quote endpoint — historical candles
 * carry no such data, so {@code HistoricalFundamentalsClient} always leaves
 * them {@code null}. Fields Groww omits for a given symbol are left
 * {@code null} rather than defaulted, so the CSV faithfully reflects what
 * the data source actually returned.
 */
public record StockFundamentals(
        String symbol,
        String exchange,
        String segment,
        Double lastPrice,
        Double open,
        Double dayHigh,
        Double dayLow,
        Double previousClose,
        Double dayChange,
        Double dayChangePerc,
        Long volume,
        Double marketCap,
        Double averagePrice,
        Double week52High,
        Double week52Low,
        Double upperCircuitLimit,
        Double lowerCircuitLimit) {
}
