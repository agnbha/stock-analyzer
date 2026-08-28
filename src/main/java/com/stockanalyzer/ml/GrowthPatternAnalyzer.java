package com.stockanalyzer.ml;

import com.stockanalyzer.model.GrowthAnalysisResult;
import com.stockanalyzer.model.StockCandleSeries;

/**
 * Runs a candle series through a growth-pattern model. The implementation is
 * free to call a local model, a remote ML microservice, or a hosted API —
 * callers only depend on this abstraction (DIP), so the model can be swapped
 * without touching {@link com.stockanalyzer.service.StockGrowthAnalysisService}.
 */
public interface GrowthPatternAnalyzer {

    GrowthAnalysisResult analyze(StockCandleSeries series);
}
