package com.stockanalyzer.features;

import java.util.List;
import java.util.Map;

/**
 * One candle's features, in a fixed, named order.
 *
 * <p>The names and their order are the contract between the Java serving path
 * and the Python training path. A shared fixture asserts both sides produce the
 * same numbers - without that, the two silently drift and every metric becomes
 * meaningless.
 */
public record FeatureVector(long tsEpoch, Map<String, Double> values) {

    public double[] asArray(List<String> names) {
        double[] array = new double[names.size()];
        for (int i = 0; i < names.size(); i++) {
            array[i] = values.getOrDefault(names.get(i), 0.0);
        }
        return array;
    }
}
