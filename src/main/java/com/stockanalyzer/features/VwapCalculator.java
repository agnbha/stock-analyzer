package com.stockanalyzer.features;

import com.stockanalyzer.model.Candle;

import java.util.ArrayList;
import java.util.List;

/** Running volume-weighted average price through a session. */
public final class VwapCalculator {

    private VwapCalculator() {
    }

    /** VWAP after each candle; index-aligned with the input. */
    public static double[] running(List<Candle> candles) {
        double[] vwap = new double[candles.size()];
        double cumulativeValue = 0;
        double cumulativeVolume = 0;
        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            double typical = (candle.high() + candle.low() + candle.close()) / 3.0;
            cumulativeValue += typical * candle.volume();
            cumulativeVolume += candle.volume();
            vwap[i] = cumulativeVolume > 0 ? cumulativeValue / cumulativeVolume : candle.close();
        }
        return vwap;
    }

    public static List<Double> runningAsList(List<Candle> candles) {
        double[] values = running(candles);
        List<Double> list = new ArrayList<>(values.length);
        for (double value : values) {
            list.add(value);
        }
        return list;
    }
}
