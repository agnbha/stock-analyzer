package com.stockanalyzer.canslim;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds daily bar series for the rule tests: a close and a volume per
 * session, with each bar opening at the previous close, so a bar's high and low
 * are simply the larger and smaller of its open and close.
 */
final class TestBars {

    static final LocalDate START = LocalDate.of(2025, 1, 1);

    private final List<double[]> sessions = new ArrayList<>();
    private double last = Double.NaN;

    static TestBars from(double firstClose) {
        TestBars bars = new TestBars();
        bars.add(firstClose, 1000);
        return bars;
    }

    /** Moves linearly to {@code close} over {@code sessions} bars. */
    TestBars trend(double close, int count) {
        double start = last;
        for (int i = 1; i <= count; i++) {
            add(start + (close - start) * i / count, 1000);
        }
        return this;
    }

    /** Swings between {@code low} and {@code high} closes for {@code count} bars. */
    TestBars range(double low, double high, int count) {
        for (int i = 0; i < count; i++) {
            add(i % 2 == 0 ? low + (high - low) * 0.3 : low + (high - low) * 0.7, 1000);
        }
        // Touch both ends once, so the range really is low..high.
        add(low, 1000);
        add(high, 1000);
        return this;
    }

    TestBars add(double close, long volume) {
        sessions.add(new double[]{close, volume});
        last = close;
        return this;
    }

    List<DailyBar> build() {
        List<DailyBar> bars = new ArrayList<>();
        double previous = sessions.getFirst()[0];
        for (int i = 0; i < sessions.size(); i++) {
            double close = sessions.get(i)[0];
            double open = previous;
            bars.add(new DailyBar(START.plusDays(i), open, Math.max(open, close), Math.min(open, close),
                    close, (long) sessions.get(i)[1]));
            previous = close;
        }
        return bars;
    }
}
