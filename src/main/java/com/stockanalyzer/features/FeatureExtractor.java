package com.stockanalyzer.features;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.EventType;
import com.stockanalyzer.model.MarketEvent;
import com.stockanalyzer.model.TradingSession;
import com.stockanalyzer.util.MarketClock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the per-candle feature vectors the model scores.
 *
 * <p>Everything here is computable from data already in the local database, so
 * scoring costs no extra API calls, and training and serving can be fed from
 * exactly the same source.
 */
public final class FeatureExtractor {

    /** The feature contract. Append only - reordering silently invalidates a trained model. */
    public static final List<String> FEATURE_NAMES = List.of(
            "minutes_since_open",
            "session_progress_sin",
            "session_progress_cos",
            "day_of_week",
            "ret_1",
            "ret_5",
            "ret_15",
            "ret_30",
            "ret_60",
            "volatility_30",
            "atr_14",
            "body_ratio",
            "upper_wick_ratio",
            "lower_wick_ratio",
            "volume_z_time_of_day",
            "dist_from_vwap_pct",
            "dist_from_open_range_high_pct",
            "dist_from_open_range_low_pct",
            "dist_from_prior_close_pct",
            "dist_from_prior_high_pct",
            "position_in_day_range",
            "gap_pct",
            "event_opening_range_breakout",
            "event_vwap_reclaim",
            "event_volume_spike",
            "event_prior_day_high_break");

    private final MarketClock clock;
    private final int openingRangeMinutes;

    public FeatureExtractor(MarketClock clock) {
        this(clock, 15);
    }

    public FeatureExtractor(MarketClock clock, int openingRangeMinutes) {
        this.clock = clock;
        this.openingRangeMinutes = openingRangeMinutes;
    }

    public List<FeatureVector> extract(TradingSession session,
                                       EventDetector.PriorSessionContext context,
                                       List<MarketEvent> events) {
        List<Candle> candles = session.candles();
        List<FeatureVector> vectors = new ArrayList<>(candles.size());
        if (candles.isEmpty()) {
            return vectors;
        }

        double[] vwap = VwapCalculator.running(candles);
        int sessionLength = Math.max(clock.sessionLengthMinutes(), 1);

        double openingHigh = Double.MIN_VALUE;
        double openingLow = Double.MAX_VALUE;
        for (Candle candle : candles) {
            if (clock.minutesSinceOpen(candle.epochSeconds()) < openingRangeMinutes) {
                openingHigh = Math.max(openingHigh, candle.high());
                openingLow = Math.min(openingLow, candle.low());
            }
        }
        if (openingHigh == Double.MIN_VALUE) {
            openingHigh = candles.getFirst().high();
            openingLow = candles.getFirst().low();
        }

        Map<EventType, Set<Long>> eventTimestamps = new LinkedHashMap<>();
        for (MarketEvent event : events) {
            eventTimestamps.computeIfAbsent(event.type(), key -> new HashSet<>()).add(event.tsEpoch());
        }

        double runningHigh = Double.MIN_VALUE;
        double runningLow = Double.MAX_VALUE;
        double gapPct = context.priorClose() == null || context.priorClose() <= 0
                ? 0.0
                : (candles.getFirst().open() - context.priorClose()) / context.priorClose() * 100.0;

        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            runningHigh = Math.max(runningHigh, candle.high());
            runningLow = Math.min(runningLow, candle.low());

            int minute = clock.minutesSinceOpen(candle.epochSeconds());
            double progress = Math.min(Math.max(minute / (double) sessionLength, 0.0), 1.0);
            double range = Math.max(candle.high() - candle.low(), 1e-9);
            double dayRange = Math.max(runningHigh - runningLow, 1e-9);

            Map<String, Double> values = new LinkedHashMap<>();
            values.put("minutes_since_open", (double) minute);
            values.put("session_progress_sin", Math.sin(2 * Math.PI * progress));
            values.put("session_progress_cos", Math.cos(2 * Math.PI * progress));
            values.put("day_of_week", (double) session.sessionDate().getDayOfWeek().getValue());
            values.put("ret_1", returnOver(candles, i, 1));
            values.put("ret_5", returnOver(candles, i, 5));
            values.put("ret_15", returnOver(candles, i, 15));
            values.put("ret_30", returnOver(candles, i, 30));
            values.put("ret_60", returnOver(candles, i, 60));
            values.put("volatility_30", volatility(candles, i, 30));
            values.put("atr_14", averageTrueRange(candles, i, 14));
            values.put("body_ratio", (candle.close() - candle.open()) / range);
            values.put("upper_wick_ratio", (candle.high() - Math.max(candle.open(), candle.close())) / range);
            values.put("lower_wick_ratio", (Math.min(candle.open(), candle.close()) - candle.low()) / range);
            values.put("volume_z_time_of_day", context.volumeProfile().zScore(minute, candle.volume()));
            values.put("dist_from_vwap_pct", pct(candle.close(), vwap[i]));
            values.put("dist_from_open_range_high_pct", pct(candle.close(), openingHigh));
            values.put("dist_from_open_range_low_pct", pct(candle.close(), openingLow));
            values.put("dist_from_prior_close_pct",
                    context.priorClose() == null ? 0.0 : pct(candle.close(), context.priorClose()));
            values.put("dist_from_prior_high_pct",
                    context.priorHigh() == null ? 0.0 : pct(candle.close(), context.priorHigh()));
            values.put("position_in_day_range", (candle.close() - runningLow) / dayRange);
            values.put("gap_pct", gapPct);
            values.put("event_opening_range_breakout",
                    flag(eventTimestamps, EventType.OPENING_RANGE_BREAKOUT, candle.epochSeconds()));
            values.put("event_vwap_reclaim", flag(eventTimestamps, EventType.VWAP_RECLAIM, candle.epochSeconds()));
            values.put("event_volume_spike", flag(eventTimestamps, EventType.VOLUME_SPIKE, candle.epochSeconds()));
            values.put("event_prior_day_high_break",
                    flag(eventTimestamps, EventType.PRIOR_DAY_HIGH_BREAK, candle.epochSeconds()));

            vectors.add(new FeatureVector(candle.epochSeconds(), Map.copyOf(values)));
        }
        return vectors;
    }

    private static double flag(Map<EventType, Set<Long>> events, EventType type, long tsEpoch) {
        Set<Long> timestamps = events.get(type);
        return timestamps != null && timestamps.contains(tsEpoch) ? 1.0 : 0.0;
    }

    private static double pct(double value, double reference) {
        return reference == 0 ? 0.0 : (value - reference) / reference * 100.0;
    }

    private static double returnOver(List<Candle> candles, int index, int lookback) {
        int from = index - lookback;
        if (from < 0) {
            return 0.0;
        }
        double past = candles.get(from).close();
        return past == 0 ? 0.0 : (candles.get(index).close() - past) / past * 100.0;
    }

    private static double volatility(List<Candle> candles, int index, int window) {
        int from = Math.max(index - window + 1, 1);
        if (index <= from) {
            return 0.0;
        }
        List<Double> returns = new ArrayList<>();
        for (int i = from; i <= index; i++) {
            double previous = candles.get(i - 1).close();
            if (previous > 0) {
                returns.add((candles.get(i).close() - previous) / previous * 100.0);
            }
        }
        if (returns.size() < 2) {
            return 0.0;
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum() / (returns.size() - 1);
        return Math.sqrt(variance);
    }

    private static double averageTrueRange(List<Candle> candles, int index, int window) {
        int from = Math.max(index - window + 1, 1);
        if (index < from) {
            return 0.0;
        }
        double sum = 0;
        int count = 0;
        for (int i = from; i <= index; i++) {
            Candle candle = candles.get(i);
            double previousClose = candles.get(i - 1).close();
            double trueRange = Math.max(candle.high() - candle.low(),
                    Math.max(Math.abs(candle.high() - previousClose), Math.abs(candle.low() - previousClose)));
            sum += trueRange;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }
}
