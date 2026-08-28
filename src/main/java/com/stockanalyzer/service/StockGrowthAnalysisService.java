package com.stockanalyzer.service;

import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.ml.GrowthPatternAnalyzer;
import com.stockanalyzer.model.GrowthAnalysisResult;
import com.stockanalyzer.model.StockAnalysisOutcome;
import com.stockanalyzer.model.StockCandleSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Orchestrates candle fetching and ML analysis for a batch of symbols.
 *
 * <p>Depends only on the {@link CandleDataClient} and {@link GrowthPatternAnalyzer}
 * abstractions (Dependency Inversion), so it can be unit tested with fakes and
 * is unaffected by swapping the data provider or the ML backend.
 */
public final class StockGrowthAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(StockGrowthAnalysisService.class);

    private final CandleDataClient candleDataClient;
    private final GrowthPatternAnalyzer growthPatternAnalyzer;
    private final ExecutorService executor;

    public StockGrowthAnalysisService(CandleDataClient candleDataClient,
                                       GrowthPatternAnalyzer growthPatternAnalyzer,
                                       ExecutorService executor) {
        this.candleDataClient = candleDataClient;
        this.growthPatternAnalyzer = growthPatternAnalyzer;
        this.executor = executor;
    }

    public List<StockAnalysisOutcome> analyzeSymbols(List<String> symbols, String exchange, String segment,
                                                       LocalDateTime startTime, LocalDateTime endTime,
                                                       int intervalMinutes) {
        List<CompletableFuture<StockAnalysisOutcome>> futures = symbols.stream()
                .map(symbol -> CompletableFuture.supplyAsync(
                        () -> analyzeOne(symbol, exchange, segment, startTime, endTime, intervalMinutes), executor))
                .toList();

        return futures.stream().map(CompletableFuture::join).toList();
    }

    private StockAnalysisOutcome analyzeOne(String symbol, String exchange, String segment,
                                             LocalDateTime startTime, LocalDateTime endTime, int intervalMinutes) {
        try {
            StockCandleSeries series = candleDataClient.fetchCandles(symbol, exchange, segment, startTime, endTime, intervalMinutes);
            if (series.candles().isEmpty()) {
                return StockAnalysisOutcome.failure(symbol, "No candle data returned");
            }
            GrowthAnalysisResult result = growthPatternAnalyzer.analyze(series);
            return StockAnalysisOutcome.success(result);
        } catch (Exception e) {
            log.warn("Failed to analyze {}: {}", symbol, e.getMessage());
            return StockAnalysisOutcome.failure(symbol, e.getMessage());
        }
    }
}
