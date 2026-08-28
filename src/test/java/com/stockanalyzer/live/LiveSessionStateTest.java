package com.stockanalyzer.live;

import com.stockanalyzer.model.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveSessionStateTest {

    private static final long OPEN = 1756000000L;

    @Test
    @DisplayName("the still-forming candle is excluded from anything analysed or written")
    void excludesTheFormingCandle() {
        LiveSessionState state = new LiveSessionState("RELIANCE", 1);
        state.merge(List.of(candle(OPEN, 100), candle(OPEN + 60, 101), candle(OPEN + 120, 102)));

        // 30 seconds into the third candle's minute.
        long now = OPEN + 150;

        assertTrue(state.lastCandleProvisional(now));
        assertEquals(2, state.completedCandles(now).size());
        assertEquals(3, state.allCandles().size(), "it is still shown, just not analysed");
    }

    @Test
    @DisplayName("once the interval has passed the candle counts as final")
    void settlesWhenTheIntervalEnds() {
        LiveSessionState state = new LiveSessionState("RELIANCE", 1);
        state.merge(List.of(candle(OPEN, 100), candle(OPEN + 60, 101)));

        long now = OPEN + 121;

        assertFalse(state.lastCandleProvisional(now));
        assertEquals(2, state.completedCandles(now).size());
    }

    @Test
    @DisplayName("a re-fetched candle replaces the earlier version of itself")
    void laterFetchesWin() {
        LiveSessionState state = new LiveSessionState("RELIANCE", 1);
        state.merge(List.of(candle(OPEN, 100)));
        state.merge(List.of(candle(OPEN, 107)));

        assertEquals(1, state.allCandles().size());
        assertEquals(107.0, state.lastPrice(), 0.0001, "the settled value replaced the provisional one");
    }

    @Test
    @DisplayName("the watermark is where the next poll resumes")
    void watermarkTracksTheNewestCandle() {
        LiveSessionState state = new LiveSessionState("RELIANCE", 1);
        assertEquals(0, state.watermark());

        state.merge(List.of(candle(OPEN, 100), candle(OPEN + 60, 101)));

        assertEquals(OPEN + 60, state.watermark());
    }

    private static Candle candle(long ts, double close) {
        return new Candle(ts, close, close + 1, close - 1, close, 1000);
    }
}
