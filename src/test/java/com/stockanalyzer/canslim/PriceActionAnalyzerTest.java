package com.stockanalyzer.canslim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceActionAnalyzerTest {

    private final PriceActionAnalyzer analyzer = new PriceActionAnalyzer(CanSlimCriteria.defaults());

    /** A 50% run-up to 150, then eight weeks swinging between 138 and 146. */
    private static TestBars baseUnder150() {
        return TestBars.from(100).trend(150, 70).range(138, 146, 38);
    }

    @Test
    @DisplayName("the pivot is the base's peak, not a high from inside the consolidation")
    void pivotIsThePeak() {
        PriceAction pa = analyzer.analyze(baseUnder150().add(144, 1000).build());

        assertNotNull(pa.base());
        assertEquals(150.0, pa.base().pivot(), 1e-9);
        assertEquals(41, pa.base().sessions());
        assertEquals(8.0, pa.base().depthPct(), 0.01);
        assertEquals(-4.0, pa.base().pctFromPivot(), 0.01);
        assertNull(pa.breakout());
    }

    @Test
    @DisplayName("a close through the pivot on 1.4x volume is a breakout")
    void breakoutOnVolume() {
        PriceAction pa = analyzer.analyze(baseUnder150().add(152, 2000).build());

        assertNotNull(pa.breakout());
        assertEquals(0, pa.breakout().sessionsAgo());
        assertEquals(150.0, pa.breakout().pivot(), 1e-9);
        assertEquals(2.0, pa.breakout().volumeRatio(), 0.01);
    }

    @Test
    @DisplayName("the same close on ordinary volume is not")
    void noBreakoutWithoutVolume() {
        PriceAction pa = analyzer.analyze(baseUnder150().add(152, 1100).build());

        assertNull(pa.breakout());
    }

    @Test
    @DisplayName("a breakout is remembered against the pivot that stood that day")
    void breakoutRemembered() {
        PriceAction pa = analyzer.analyze(baseUnder150().add(152, 2000).add(156, 1500).add(160, 1500).build());

        assertEquals(2, pa.breakout().sessionsAgo());
        assertEquals(150.0, pa.breakout().pivot(), 1e-9);
        assertEquals(6.67, pa.breakout().pctFromPivot(), 0.01);
    }

    @Test
    @DisplayName("a correction deeper than the limit is a broken stock, not a base")
    void tooDeep() {
        PriceAction pa = analyzer.analyze(TestBars.from(100).trend(150, 70).range(90, 140, 38).build());

        assertNull(pa.base());
    }

    @Test
    @DisplayName("a stock still making new highs is not in a base")
    void stillRising() {
        PriceAction pa = analyzer.analyze(TestBars.from(100).trend(200, 120).build());

        assertNull(pa.base());
        assertEquals(0.0, pa.pctFromHigh(), 1e-9);
    }

    @Test
    @DisplayName("a close below the 50-day average is dated and carries its volume")
    void fiftyDayBreak() {
        // The 50-day average of that trend sits near 148.
        List<DailyBar> bars = TestBars.from(100).trend(160, 120).add(140, 3000).build();

        PriceAction pa = analyzer.analyze(bars);

        assertNotNull(pa.fiftyDayBreak());
        assertEquals(bars.getLast().date(), pa.fiftyDayBreak().date());
        assertEquals(3.0, pa.fiftyDayBreak().volumeRatio(), 0.01);
    }

    @Test
    @DisplayName("relative strength weights the latest quarter double, and needs one quarter")
    void weightedReturn() {
        assertNull(PriceActionAnalyzer.weightedReturn(TestBars.from(100).trend(110, 40).build()));

        // 63 sessions back the close was 100, and that is all the history there is.
        Double oneQuarter = PriceActionAnalyzer.weightedReturn(TestBars.from(100).trend(120, 63).build());
        assertEquals(20.0, oneQuarter, 1e-9);
    }

    @Test
    @DisplayName("up-day volume against down-day volume")
    void upDownVolume() {
        PriceAction pa = analyzer.analyze(TestBars.from(100).add(101, 3000).add(100, 1000).add(102, 3000).build());

        assertEquals(6.0, pa.upDownVolumeRatio(), 1e-9);
        assertFalse(pa.exhaustionDay());
        assertTrue(pa.advanceFromLowPct() > 0);
    }
}
