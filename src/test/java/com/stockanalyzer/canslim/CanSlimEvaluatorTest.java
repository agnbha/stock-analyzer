package com.stockanalyzer.canslim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanSlimEvaluatorTest {

    private final CanSlimEvaluator evaluator = new CanSlimEvaluator(CanSlimCriteria.defaults());
    private final PriceActionAnalyzer analyzer = new PriceActionAnalyzer(CanSlimCriteria.defaults());

    private static final MarketAssessment UPTREND = market(MarketState.CONFIRMED_UPTREND);
    private static final ScreenedStock GROWER = new ScreenedStock("GROW", "Grower", Map.of(
            ScreenerRatios.QTR_PROFIT_GROWTH, 40.0,
            ScreenerRatios.QTR_SALES_GROWTH, 28.0,
            ScreenerRatios.PROFIT_GROWTH_3Y, 32.0,
            ScreenerRatios.ROE, 22.0,
            ScreenerRatios.FII_CHANGE, 0.8,
            ScreenerRatios.DII_CHANGE, -0.2));

    private static MarketAssessment market(MarketState state) {
        return new MarketAssessment("IDX", LocalDate.of(2025, 6, 1), state, 100.0, 95.0, null, 0, "test");
    }

    private static TestBars baseUnder150() {
        return TestBars.from(100).trend(150, 70).range(138, 146, 38);
    }

    private CanSlimScore screened(TestBars bars, MarketAssessment market) {
        return evaluator.evaluate("GROW", CanSlimScore.Source.SCREENER, GROWER, analyzer.analyze(bars.build()),
                90, market, null);
    }

    @Test
    @DisplayName("the fundamental letters come from the screener's ratios")
    void letters() {
        CanSlimScore score = screened(baseUnder150().add(144, 1000), UPTREND);

        assertEquals(Boolean.TRUE, score.letters().c());
        assertEquals(Boolean.TRUE, score.letters().a());
        assertEquals(Boolean.TRUE, score.letters().i(), "FII +0.8 and DII -0.2 is a net increase");
        assertEquals(Boolean.TRUE, score.letters().l());
        assertEquals(Boolean.TRUE, score.letters().m());
        assertEquals(0.6, score.institutionalChange(), 1e-9);
    }

    @Test
    @DisplayName("a missing ratio leaves its letter unknown, not failed")
    void unknownLetter() {
        CanSlimScore score = evaluator.evaluate("X", CanSlimScore.Source.SCREENER,
                new ScreenedStock("X", "X", Map.of()), analyzer.analyze(baseUnder150().add(144, 1000).build()),
                null, market(MarketState.UNKNOWN), null);

        assertNull(score.letters().c());
        assertNull(score.letters().l());
        assertNull(score.letters().m());
        assertEquals("??NS???", score.letters().display());
    }

    @Test
    @DisplayName("just under the pivot of a sound base is a setup about to break out")
    void watchSetup() {
        CanSlimScore score = screened(baseUnder150().add(144, 1000), UPTREND);

        assertEquals(CanSlimSignal.WATCH_SETUP, score.signal());
        assertTrue(score.reason().contains("under the pivot 150.00"), score.reason());
    }

    @Test
    @DisplayName("a breakout on volume inside the buy range is a buy")
    void breakoutBuy() {
        assertEquals(CanSlimSignal.BUY_BREAKOUT, screened(baseUnder150().add(152, 2000), UPTREND).signal());
        assertEquals(CanSlimSignal.BUY_ZONE,
                screened(baseUnder150().add(152, 2000).add(154, 1200), UPTREND).signal());
    }

    @Test
    @DisplayName("past the buy range it is extended, not a buy")
    void extended() {
        CanSlimScore score = screened(baseUnder150().add(152, 2000).add(158, 1500).add(162, 1500), UPTREND);

        assertEquals(CanSlimSignal.EXTENDED, score.signal());
    }

    @Test
    @DisplayName("in a correction the same breakout is held back")
    void correction() {
        CanSlimScore score = screened(baseUnder150().add(152, 2000), market(MarketState.CORRECTION));

        assertEquals(CanSlimSignal.AVOID_MARKET, score.signal());
        assertTrue(score.reason().contains("BUY_BREAKOUT"));
    }

    @Test
    @DisplayName("failing earnings rule out a buy whatever the chart says")
    void failingFundamentals() {
        ScreenedStock weak = new ScreenedStock("WEAK", "Weak", Map.of(ScreenerRatios.QTR_PROFIT_GROWTH, 5.0));
        CanSlimScore score = evaluator.evaluate("WEAK", CanSlimScore.Source.SCREENER, weak,
                analyzer.analyze(baseUnder150().add(152, 2000).build()), 90, UPTREND, null);

        assertEquals(CanSlimSignal.NONE, score.signal());
        assertTrue(score.reason().contains("C (current earnings)"));
    }

    @Test
    @DisplayName("a failed breakout 8% under its pivot is a stop-loss sell")
    void stopLoss() {
        CanSlimScore score = screened(baseUnder150().add(152, 2000).add(145, 1500).add(137, 1500), UPTREND);

        assertEquals(CanSlimSignal.SELL_STOP_LOSS, score.signal());
        assertEquals(150.0, score.buyPoint(), 1e-9);
    }

    @Test
    @DisplayName("a holding is measured from its own cost")
    void holdingStopLoss() {
        CanSlimScore score = evaluator.evaluate("HELD", CanSlimScore.Source.HOLDING, null,
                analyzer.analyze(TestBars.from(100).trend(120, 60).trend(110, 5).build()), 50, UPTREND, 121.0);

        assertEquals(CanSlimSignal.SELL_STOP_LOSS, score.signal());
        assertTrue(score.reason().contains("holding cost"));
    }

    @Test
    @DisplayName("a heavy-volume close under the 50-day average ends a rally")
    void trendBreak() {
        CanSlimScore score = evaluator.evaluate("RAN", CanSlimScore.Source.UNIVERSE, null,
                analyzer.analyze(TestBars.from(100).trend(160, 120).add(140, 3000).build()), 70, UPTREND, null);

        assertEquals(CanSlimSignal.SELL_TREND_BREAK, score.signal());
    }

    @Test
    @DisplayName("a quarter-gain in three weeks, well above the 50-day, is a climax")
    void climax() {
        CanSlimScore score = evaluator.evaluate("HOT", CanSlimScore.Source.UNIVERSE, null,
                analyzer.analyze(TestBars.from(100).trend(110, 80).trend(145, 15).build()), 99, UPTREND, null);

        assertEquals(CanSlimSignal.SELL_CLIMAX, score.signal());
    }

    @Test
    @DisplayName("a stock that is neither screened nor in trouble has no signal")
    void universeOnly() {
        CanSlimScore score = evaluator.evaluate("CALM", CanSlimScore.Source.UNIVERSE, null,
                analyzer.analyze(baseUnder150().add(152, 2000).build()), 90, UPTREND, null);

        assertEquals(CanSlimSignal.NONE, score.signal());
    }
}
