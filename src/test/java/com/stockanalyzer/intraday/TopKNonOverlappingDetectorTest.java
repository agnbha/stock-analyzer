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

    @Test
    @DisplayName("the minimum hold is part of the version, and the original one-candle hold keeps its tag")
    void versionEncodesTheMinimumHold() {
        assertEquals("topk-nonoverlap/highlow/min0.5/hold5/v1",
                new TopKNonOverlappingDetector(3, PriceBasis.HIGH_LOW, 5, 0.5).version());
        assertEquals("topk-nonoverlap/highlow/hold5/v1",
                new TopKNonOverlappingDetector(3, PriceBasis.HIGH_LOW, 5, 0.0).version());
        assertEquals("topk-nonoverlap/highlow/min0.5/v1",
                new TopKNonOverlappingDetector(3, PriceBasis.HIGH_LOW, 1, 0.5).version(),
                "a one-candle hold is what the rows already in the database were written under");
    }

    @Test
    @DisplayName("a minimum in minutes becomes enough candles, rounded up, never fewer than the candle minimum")
    void holdCandlesCoversTheTimeAndTheCandleMinimum() {
        assertEquals(5, TopKNonOverlappingDetector.holdCandles(5, 1, 1), "five one-minute candles");
        assertEquals(1, TopKNonOverlappingDetector.holdCandles(5, 1, 5), "one five-minute candle");
        assertEquals(3, TopKNonOverlappingDetector.holdCandles(5, 1, 2), "two two-minute candles are only four minutes");
        assertEquals(8, TopKNonOverlappingDetector.holdCandles(5, 8, 1), "the candle minimum can be the larger one");
        assertEquals(1, TopKNonOverlappingDetector.holdCandles(0, 1, 1), "no minimum still means a later candle");
    }

    @Test
    @DisplayName("with a five-minute hold no window, on any session, lasts less than five minutes or overlaps another")
    void noWindowIsShorterThanTheHold() {
        TopKNonOverlappingDetector patient = new TopKNonOverlappingDetector(3, PriceBasis.HIGH_LOW,
                TopKNonOverlappingDetector.holdCandles(5, 1, 1), 0.0);
        TopKNonOverlappingDetector hasty = new TopKNonOverlappingDetector(3, PriceBasis.HIGH_LOW, 1, 0.0);
        int windowsChecked = 0;
        int shorterWithoutTheHold = 0;

        for (long seed = 1; seed <= 60; seed++) {
            java.util.Random random = new java.util.Random(seed);
            double[] closes = new double[375];
            closes[0] = 100;
            for (int i = 1; i < closes.length; i++) {
                closes[i] = closes[i - 1] * (1 + random.nextGaussian() * 0.0015);
            }
            List<Candle> candles = Candles.ofCloses(0.1, closes);

            List<GainOpportunity> windows = patient.detect(candles);
            for (GainOpportunity window : windows) {
                assertTrue(window.durationMinutes() >= 5,
                        "seed " + seed + ": a " + window.durationMinutes() + "-minute window leaves no time to act");
                assertTrue(window.exitTs() - window.entryTs() >= 5 * 60L);
                windowsChecked++;
            }
            for (int a = 0; a < windows.size(); a++) {
                for (int b = a + 1; b < windows.size(); b++) {
                    assertTrue(windows.get(a).exitTs() < windows.get(b).entryTs()
                                    || windows.get(b).exitTs() < windows.get(a).entryTs(),
                            "seed " + seed + ": windows overlap");
                }
            }
            shorterWithoutTheHold += (int) hasty.detect(candles).stream().filter(w -> w.durationMinutes() < 5).count();
        }

        assertTrue(windowsChecked >= 150, "the test must actually look at windows, saw " + windowsChecked);
        assertTrue(shorterWithoutTheHold > 0,
                "without the hold such windows do occur, or this test proves nothing");
    }

    @Test
    @DisplayName("a spike that is over in a minute is not offered as a window when five minutes are needed")
    void aOneMinuteSpikeIsNotAWindow() {
        // 100 -> 130 in a single candle, then straight back: the best trade on paper, and unactionable.
        List<Candle> candles = Candles.ofCloses(0, 100, 100, 100, 130, 100, 100, 100, 100, 101, 102, 103, 104, 105, 106);
        TopKNonOverlappingDetector patient = new TopKNonOverlappingDetector(1, PriceBasis.CLOSE_CLOSE, 5, 0.0);

        GainOpportunity best = patient.detect(candles).getFirst();

        assertTrue(best.durationMinutes() >= 5);
        assertTrue(best.gainPct() < 10, "the spike cannot be the exit of a five-minute window, got " + best.gainPct());
    }

    @Test
    @DisplayName("the gain floor is part of the version, so a raised floor is a parallel series")
    void versionEncodesTheGainFloor() {
        assertEquals("topk-nonoverlap/highlow/min0.5/v1",
                new TopKNonOverlappingDetector(3, PriceBasis.HIGH_LOW, 1, 0.5).version());
        // Trailing zeros would make the same floor look like two different detectors.
        assertEquals("topk-nonoverlap/highlow/min1/v1",
                new TopKNonOverlappingDetector(3, PriceBasis.HIGH_LOW, 1, 1.00).version());
        // A floorless detector keeps the tag the existing rows were written under.
        assertEquals("topk-nonoverlap/highlow/v1",
                new TopKNonOverlappingDetector(3, PriceBasis.HIGH_LOW, 1, 0.0).version());
    }

    @Test
    @DisplayName("a day whose best move is under the floor yields no windows at all")
    void wholeDayDroppedWhenNothingClearsTheFloor() {
        TopKNonOverlappingDetector halfPercent =
                new TopKNonOverlappingDetector(3, PriceBasis.CLOSE_CLOSE, 1, 0.5);
        // Drifts up 0.3% at its very best - real movement, but not a tradable one.
        List<Candle> candles = Candles.ofCloses(0, 100.0, 100.1, 100.3, 100.2, 100.25);

        assertEquals(List.of(), halfPercent.detect(candles),
                "no meaningful trade was possible, so there is no top-3 to record");
    }

    @Test
    @DisplayName("the floor drops the weak windows without disturbing the strong ones")
    void floorKeepsOnlyTheWindowsThatClearIt() {
        // Three separate moves, each needing a dip before it so the earlier window
        // cannot simply swallow the later high: +10%, +2%, then a +0.2% shuffle
        // that must not survive the floor.
        List<Candle> candles = Candles.ofCloses(0,
                100, 110,          // +10%
                105, 100, 102,     // +2% off the dip to 100
                101, 100, 100.2);  // +0.2% - real, but not worth a round trip

        List<GainOpportunity> found =
                new TopKNonOverlappingDetector(3, PriceBasis.CLOSE_CLOSE, 1, 0.5).detect(candles);

        assertEquals(2, found.size(), "the third window is below the floor and is not recorded");
        for (GainOpportunity opportunity : found) {
            assertTrue(opportunity.gainPct() >= 0.5,
                    "recorded " + opportunity.gainPct() + "%, which is under the floor");
        }
        // Ranks stay contiguous: dropping a window must not leave a gap at rank 3.
        assertEquals(List.of(1, 2), found.stream().map(GainOpportunity::rank).toList());
    }
}
