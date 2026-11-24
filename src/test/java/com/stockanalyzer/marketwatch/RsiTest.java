package com.stockanalyzer.marketwatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RsiTest {

    /** StockCharts' worked example of Wilder's 14-day RSI. */
    private static final List<Double> CLOSES = List.of(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84,
            46.08, 45.89, 46.03, 45.61, 46.28, 46.28, 46.00, 46.03, 46.41, 46.22, 45.64, 46.21, 46.25, 45.71, 46.45,
            45.78, 45.35, 44.03, 44.18, 44.22, 44.57, 43.42, 42.66, 43.13);
    private static final double[] EXPECTED = {70.53, 66.32, 66.55, 69.41, 66.36, 57.97, 62.93, 63.26, 56.06, 62.38,
            54.71, 50.42, 39.99, 41.46, 41.87, 45.46, 37.30, 33.08, 37.77};

    @Test
    @DisplayName("Wilder's RSI, as in the textbook example: none until 14 changes, then carried forward")
    void textbookExample() {
        List<Double> rsi = Rsi.wilder(CLOSES, 14);

        for (int i = 0; i < 14; i++) {
            assertNull(rsi.get(i), "no RSI before 14 changes, at " + i);
        }
        // Exactly: the first 14 changes gain 3.34 and lose 1.40 in all.
        assertEquals(100 - 100 / (1 + 3.34 / 1.40), rsi.get(14), 1e-9);
        // The textbook rounds its averages to two places, which moves its figures by up to 0.07, less as it goes on.
        for (int i = 0; i < EXPECTED.length; i++) {
            assertEquals(EXPECTED[i], rsi.get(14 + i), 0.1, "at close " + (14 + i));
        }
    }

    @Test
    @DisplayName("100 with no loss to average, 0 with no gain, and nothing for too few closes")
    void edges() {
        List<Double> rising = java.util.stream.IntStream.range(0, 20).mapToObj(i -> 100.0 + i).toList();
        List<Double> falling = java.util.stream.IntStream.range(0, 20).mapToObj(i -> 100.0 - i).toList();
        assertEquals(100.0, Rsi.wilder(rising, 14).getLast());
        assertEquals(0.0, Rsi.wilder(falling, 14).getLast());
        assertEquals(Collections.nCopies(14, null), Rsi.wilder(rising.subList(0, 14), 14));
    }
}
