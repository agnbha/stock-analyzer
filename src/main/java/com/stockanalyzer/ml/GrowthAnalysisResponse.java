package com.stockanalyzer.ml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.stockanalyzer.model.GrowthTrend;

/**
 * Expected wire format from the ML service:
 * {"symbol": "...", "trend": "BULLISH", "growthScore": 0.42, "confidence": 0.87}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record GrowthAnalysisResponse(String symbol, GrowthTrend trend, double growthScore, double confidence) {
}
