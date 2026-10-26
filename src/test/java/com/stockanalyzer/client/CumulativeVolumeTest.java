package com.stockanalyzer.client;

import com.stockanalyzer.model.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CumulativeVolumeTest {

    // 09:15 IST on 2026-09-11, and the same time the next day
    private static final long DAY1 = 1_789_098_300L;
    private static final long DAY2 = DAY1 + 86_400;

    private static Candle at(long ts, long volume) {
        return new Candle(ts, 1, 1, 1, 1, volume);
    }

    private static List<Long> volumes(List<Candle> candles) {
        return candles.stream().map(Candle::volume).toList();
    }

    @Test
    @DisplayName("running totals become each candle's own volume")
    void differencesRunningTotal() {
        List<Candle> out = CumulativeVolume.toPerCandle(List.of(
                at(DAY1, 200), at(DAY1 + 60, 300), at(DAY1 + 120, 350)));
        assertEquals(List.of(200L, 100L, 50L), volumes(out));
    }

    @Test
    @DisplayName("a zero-volume glitch is neither negative nor credited with the next catch-up")
    void zeroGlitch() {
        List<Candle> out = CumulativeVolume.toPerCandle(List.of(
                at(DAY1, 200), at(DAY1 + 60, 0), at(DAY1 + 120, 350)));
        assertEquals(List.of(200L, 0L, 150L), volumes(out));
    }

    @Test
    @DisplayName("each session restarts from zero")
    void sessionsRestart() {
        List<Candle> out = CumulativeVolume.toPerCandle(List.of(
                at(DAY1, 200), at(DAY1 + 60, 300), at(DAY2, 80), at(DAY2 + 60, 100)));
        assertEquals(List.of(200L, 100L, 80L, 20L), volumes(out));
    }
}
