package com.stockanalyzer.model;

/**
 * Outcome of fetching fundamentals for a single symbol. Exactly one of
 * {@code fundamentals} or {@code errorMessage} is populated, so a failure on
 * one stock never aborts the whole export.
 */
public record FundamentalsOutcome(String symbol, StockFundamentals fundamentals, String errorMessage) {

    public static FundamentalsOutcome success(StockFundamentals fundamentals) {
        return new FundamentalsOutcome(fundamentals.symbol(), fundamentals, null);
    }

    public static FundamentalsOutcome failure(String symbol, String errorMessage) {
        return new FundamentalsOutcome(symbol, null, errorMessage);
    }

    public boolean isSuccess() {
        return fundamentals != null;
    }
}
