package com.stockanalyzer.marketwatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The relative strength index, as Wilder defined it and as TradingView's {@code ta.rsi} computes it. */
public final class Rsi {

    private Rsi() {
    }

    /**
     * The RSI of each close, oldest first. The average gain and loss start as the plain average of the first
     * {@code period} changes and then carry forward Wilder's way, each new change weighing 1/period. Null until there
     * are {@code period} changes; 100 when there has been no loss to average, 0 when no gain.
     */
    public static List<Double> wilder(List<Double> closes, int period) {
        List<Double> rsi = new ArrayList<>(Collections.nCopies(closes.size(), (Double) null));
        if (closes.size() <= period) {
            return rsi;
        }
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes.get(i) - closes.get(i - 1);
            gain += Math.max(change, 0);
            loss += Math.max(-change, 0);
        }
        gain /= period;
        loss /= period;
        rsi.set(period, of(gain, loss));
        for (int i = period + 1; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            gain = (gain * (period - 1) + Math.max(change, 0)) / period;
            loss = (loss * (period - 1) + Math.max(-change, 0)) / period;
            rsi.set(i, of(gain, loss));
        }
        return rsi;
    }

    private static double of(double gain, double loss) {
        if (loss == 0) {
            return 100;
        }
        if (gain == 0) {
            return 0;
        }
        return 100 - 100 / (1 + gain / loss);
    }
}
