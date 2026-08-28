package com.stockanalyzer.ml;

import com.stockanalyzer.model.Candle;

/** Wire format for one candle sent to the ML service. */
record CandleDto(long timestamp, double open, double high, double low, double close, long volume) {

    static CandleDto from(Candle candle) {
        return new CandleDto(candle.epochSeconds(), candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
    }
}
