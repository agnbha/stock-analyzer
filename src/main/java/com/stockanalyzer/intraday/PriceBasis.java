package com.stockanalyzer.intraday;

import com.stockanalyzer.model.Candle;

/**
 * Which prices a gain is measured between.
 *
 * <p>{@link #HIGH_LOW} answers "what was actually available in the tape" - buy
 * at the entry candle's low, sell at the exit candle's high. {@link #CLOSE_CLOSE}
 * is the conservative reading, using closes at both ends.
 */
public enum PriceBasis {

    HIGH_LOW("highlow") {
        @Override
        public double entryPrice(Candle candle) {
            return candle.low();
        }

        @Override
        public double exitPrice(Candle candle) {
            return candle.high();
        }
    },

    CLOSE_CLOSE("closeclose") {
        @Override
        public double entryPrice(Candle candle) {
            return candle.close();
        }

        @Override
        public double exitPrice(Candle candle) {
            return candle.close();
        }
    };

    private final String tag;

    PriceBasis(String tag) {
        this.tag = tag;
    }

    public abstract double entryPrice(Candle candle);

    public abstract double exitPrice(Candle candle);

    /** Short form used in the detector version string stored alongside every result. */
    public String tag() {
        return tag;
    }
}
