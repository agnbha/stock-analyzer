package com.stockanalyzer.features;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.util.MarketClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mean and spread of volume for each minute of the session, learned from stored
 * candles.
 *
 * <p>Intraday volume is strongly U-shaped, so a raw volume z-score mostly
 * encodes "it is 09:20". Comparing a candle against the same minute on other
 * days is what makes a volume spike mean something.
 */
public final class TimeOfDayVolumeProfile {

    private final Map<Integer, double[]> meanAndStdevByMinute;

    private TimeOfDayVolumeProfile(Map<Integer, double[]> meanAndStdevByMinute) {
        this.meanAndStdevByMinute = meanAndStdevByMinute;
    }

    public static TimeOfDayVolumeProfile empty() {
        return new TimeOfDayVolumeProfile(Map.of());
    }

    public static TimeOfDayVolumeProfile from(List<Candle> historicalCandles, MarketClock clock) {
        Map<Integer, List<Long>> byMinute = new HashMap<>();
        for (Candle candle : historicalCandles) {
            int minute = clock.minutesSinceOpen(candle.epochSeconds());
            byMinute.computeIfAbsent(minute, key -> new ArrayList<>()).add(candle.volume());
        }
        Map<Integer, double[]> stats = new HashMap<>();
        byMinute.forEach((minute, volumes) -> {
            double mean = volumes.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = volumes.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
            stats.put(minute, new double[]{mean, Math.sqrt(variance)});
        });
        return new TimeOfDayVolumeProfile(stats);
    }

    public boolean isEmpty() {
        return meanAndStdevByMinute.isEmpty();
    }

    /** Z-score of this candle's volume against the same minute on other days; 0 when unknown. */
    public double zScore(int minutesSinceOpen, long volume) {
        double[] stats = meanAndStdevByMinute.get(minutesSinceOpen);
        if (stats == null || stats[1] <= 0) {
            return 0.0;
        }
        return (volume - stats[0]) / stats[1];
    }
}
