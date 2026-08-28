package com.stockanalyzer.model;

/**
 * Outcome of processing a single symbol end-to-end. Exactly one of
 * {@code result} or {@code errorMessage} is populated, so a failure on one
 * stock never aborts the whole batch.
 */
public record StockAnalysisOutcome(String symbol, GrowthAnalysisResult result, String errorMessage) {

    public static StockAnalysisOutcome success(GrowthAnalysisResult result) {
        return new StockAnalysisOutcome(result.symbol(), result, null);
    }

    public static StockAnalysisOutcome failure(String symbol, String errorMessage) {
        return new StockAnalysisOutcome(symbol, null, errorMessage);
    }

    public boolean isSuccess() {
        return result != null;
    }
}
