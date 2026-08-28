package com.stockanalyzer.intraday;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.GainOpportunity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopKNonOverlappingDetectorTest {

    private final TopKNonOverlappingDetector detector =
            new TopKNonOverlappingDetector(3, PriceBasis.CLOSE_CLOSE, 1, 0.0);

    @Test
    @DisplayName("finds the single best window of a session")
    void findsTheBestWindow() {
        List<Candle> candles = Candles.ofCloses(0, 100, 98, 96, 110, 108);

        List<GainOpportunity> found = detector.detect(candles);

        GainOpportunity best = found.getFirst();
        assertEquals(1, best.rank());
        assertEquals(Candles.at(2), best.entryTs(), "entry at the low");
        assertEquals(Candles.at(3), best.exitTs(), "exit at the high");
        assertEquals(14.583, best.gainPct(), 0.001);
        assertEquals(1, best.durationMinutes());
    }

    @Test
    @DisplayName("returns three windows that do not share candles")
    void returnsNonOverlappingWindows() {
        List<Candle> candles = Candles.ofCloses(0,
                100, 90, 130,      // window A: 90 -> 130
                120, 100, 125,     // window B: 100 -> 125
                124, 110, 118);    // window C: 110 -> 118

        List<GainOpportunity> found = detector.detect(candles);

        assertEquals(3, found.size());
        for (int i = 1; i < found.size(); i++) {
            assertTrue(found.get(i - 1).gainPct() >= found.get(i).gainPct(), "ranked by gain");
        }
        // No window may start before an earlier-ranked one ends and still overlap it.
        for (int i = 0; i < found.size(); i++) {
            for (int j = i + 1; j < found.size(); j++) {
                boolean disjoint = found.get(i).exitTs() < found.get(j).entryTs()
                        || found.get(j).exitTs() < found.get(i).entryTs();
                assertTrue(disjoint, "windows " + i + " and " + j + " overlap");
            }
        }
    }

    @Test
    @DisplayName("a day that only falls yields nothing rather than a fabricated window")
    void monotonicDeclineYieldsNothing() {
        List<Candle> candles = Candles.ofCloses(0, 120, 118, 115, 110, 104, 100);

        assertEquals(List.of(), detector.detect(candles));
    }

    @Test
    @DisplayName("a flat day yields nothing when a minimum gain is required")
    void flatDayYieldsNothingAboveTheFloor() {
        TopKNonOverlappingDetector withFloor =
                new TopKNonOverlappingDetector(3, PriceBasis.CLOSE_CLOSE, 1, 0.1);
        List<Candle> candles = Candles.ofCloses(0, 100, 100, 100, 100);

        assertEquals(List.of(), withFloor.detect(candles));
    }

    @Test
    @DisplayName("the exit must be at least minHoldCandles after the entry")
    void honoursTheMinimumHold() {
        // The one-candle spike from 90 to 130 is unusable once three candles must pass.
        List<Candle> candles = Candles.ofCloses(0, 100, 90, 130, 95, 96, 97, 120);
        TopKNonOverlappingDetector patient =
                new TopKNonOverlappingDetector(1, PriceBasis.CLOSE_CLOSE, 3, 0.0);

        GainOpportunity best = patient.detect(candles).getFirst();

        assertEquals(Candles.at(1), best.entryTs());
        assertEquals(Candles.at(6), best.exitTs());
        assertTrue(best.durationMinutes() >= 3);
    }

    @Test
    @DisplayName("HIGH_LOW measures the low of the entry candle to the high of the exit candle")
    void highLowUsesTheExtremes() {
        List<Candle> candles = List.of(
                Candles.candle(0, 100, 101, 99, 100),
                Candles.candle(1, 100, 102, 95, 101),
                Candles.candle(2, 101, 110, 100, 105));
        TopKNonOverlappingDetector optimistic =
                new TopKNonOverlappingDetector(1, PriceBasis.HIGH_LOW, 1, 0.0);

        GainOpportunity best = optimistic.detect(candles).getFirst();

        assertEquals(95.0, best.entryPrice(), 0.0001, "bought at the session low");
        assertEquals(110.0, best.exitPrice(), 0.0001, "sold at the later high");
        assertEquals(15.789, best.gainPct(), 0.001);
    }

    @Test
    @DisplayName("too few candles to hold a position yields nothing")
    void tooShortToTrade() {
        assertEquals(List.of(), detector.detect(Candles.ofCloses(0, 100)));
        assertEquals(List.of(), detector.detect(List.of()));
    }

    @Test
    @DisplayName("the detector version identifies the rules that produced a result")
    void versionEncodesTheRules() {
        assertEquals("topk-nonoverlap/closeclose/v1", detector.version());
        assertEquals("topk-nonoverlap/highlow/v1",
                new TopKNonOverlappingDetector(3, PriceBasis.HIGH_LOW, 1, 0).version());
    }
}
