package com.stockanalyzer.canslim;

import java.util.List;

/** Window arithmetic over chronologically ordered daily bars. Indices are inclusive. */
final class Bars {

    private Bars() {
    }

    /** Mean close of the {@code length} bars ending at {@code end}; null without that much history. */
    static Double sma(List<DailyBar> bars, int end, int length) {
        int start = end - length + 1;
        if (start < 0 || end >= bars.size()) {
            return null;
        }
        double sum = 0;
        for (int i = start; i <= end; i++) {
            sum += bars.get(i).close();
        }
        return sum / length;
    }

    /**
     * Mean volume of up to {@code length} bars before {@code index}, excluding it -
     * the baseline a day's volume is judged against. Null with fewer than
     * twenty bars, where an average says little.
     */
    static Double averageVolumeBefore(List<DailyBar> bars, int index, int length) {
        int start = Math.max(0, index - length);
        int count = index - start;
        if (count < 20) {
            return null;
        }
        double sum = 0;
        for (int i = start; i < index; i++) {
            sum += bars.get(i).volume();
        }
        return sum / count;
    }

    static double maxHigh(List<DailyBar> bars, int from, int to) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, from); i <= to; i++) {
            max = Math.max(max, bars.get(i).high());
        }
        return max;
    }

    static double minLow(List<DailyBar> bars, int from, int to) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = Math.max(0, from); i <= to; i++) {
            min = Math.min(min, bars.get(i).low());
        }
        return min;
    }

    static double pctChange(double from, double to) {
        return (to - from) / from * 100.0;
    }
}
