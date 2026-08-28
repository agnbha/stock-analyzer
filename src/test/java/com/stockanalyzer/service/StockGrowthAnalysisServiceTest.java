package com.stockanalyzer.service;

import com.stockanalyzer.client.CandleDataClient;
import com.stockanalyzer.ml.GrowthPatternAnalyzer;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.GrowthAnalysisResult;
import com.stockanalyzer.model.GrowthTrend;
import com.stockanalyzer.model.StockAnalysisOutcome;
import com.stockanalyzer.model.StockCandleSeries;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockGrowthAnalysisServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 7, 20, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 19, 0, 0);

    /** DIP in action: the service under test only ever sees these interfaces, never a real HTTP call. */
    static class FakeCandleDataClient implements CandleDataClient {
        @Override
        public StockCandleSeries fetchCandles(String symbol, String exchange, String segment,
                                               LocalDateTime startTime, LocalDateTime endTime, int intervalMinutes) {
            if (symbol.equals("BADSTOCK")) {
                throw new RuntimeException("simulated upstream failure");
            }
            if (symbol.equals("NODATA")) {
                return new StockCandleSeries(symbol, exchange, segment, List.of());
            }
            return new StockCandleSeries(symbol, exchange, segment,
                    List.of(new Candle(1000, 100, 110, 95, 105, 1000)));
        }
    }

    static class FakeGrowthPatternAnalyzer implements GrowthPatternAnalyzer {
        @Override
        public GrowthAnalysisResult analyze(StockCandleSeries series) {
            return new GrowthAnalysisResult(series.symbol(), GrowthTrend.BULLISH, 0.5, 0.9);
        }
    }

    @Test
    void analyzesEachSymbolIndependently() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            StockGrowthAnalysisService service = new StockGrowthAnalysisService(
                    new FakeCandleDataClient(), new FakeGrowthPatternAnalyzer(), executor);

            List<StockAnalysisOutcome> outcomes = service.analyzeSymbols(
                    List.of("GOODSTOCK", "BADSTOCK", "NODATA"), "NSE", "CASH", START, END, 1440);

            assertEquals(3, outcomes.size());

            StockAnalysisOutcome good = findBySymbol(outcomes, "GOODSTOCK");
            assertTrue(good.isSuccess());
            assertEquals(GrowthTrend.BULLISH, good.result().trend());

            StockAnalysisOutcome bad = findBySymbol(outcomes, "BADSTOCK");
            assertFalse(bad.isSuccess());
            assertTrue(bad.errorMessage().contains("simulated upstream failure"));

            StockAnalysisOutcome noData = findBySymbol(outcomes, "NODATA");
            assertFalse(noData.isSuccess());
        } finally {
            executor.shutdown();
        }
    }

    private static StockAnalysisOutcome findBySymbol(List<StockAnalysisOutcome> outcomes, String symbol) {
        return outcomes.stream().filter(o -> o.symbol().equals(symbol)).findFirst().orElseThrow();
    }
}
