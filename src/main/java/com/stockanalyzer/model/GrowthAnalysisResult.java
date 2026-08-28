package com.stockanalyzer.model;

/**
 * Result of running one stock's candle series through the growth-pattern ML model.
 *
 * @param growthScore  model-defined score, typically -1.0 (strong decline) to 1.0 (strong growth)
 * @param confidence   model's confidence in the prediction, 0.0 to 1.0
 */
public record GrowthAnalysisResult(String symbol, GrowthTrend trend, double growthScore, double confidence) {
}
