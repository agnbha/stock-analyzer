package com.stockanalyzer.client;

import com.stockanalyzer.model.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the feed's intraday volume - the session's running total so far - into
 * the volume traded within each candle.
 *
 * <p>The running total is differenced against its running maximum rather than
 * the previous row, because the feed sometimes reports a minute as 0: a plain
 * difference would give that minute a negative volume and credit the next one
 * with the whole day so far.
 *
 * <p>Sessions are separated by India calendar day, and the first candle of a
 * session is taken to start from zero, so a range must begin at or before the
 * session's first candle for its first row to be right.
 */
final class CumulativeVolume {

    private static final long IST_OFFSET_SECONDS = 19_800;
    private static final long SECONDS_PER_DAY = 86_400;

    private CumulativeVolume() {
    }

    static List<Candle> toPerCandle(List<Candle> candles) {
        List<Candle> result = new ArrayList<>(candles.size());
        long session = Long.MIN_VALUE;
        long runningMax = 0;
        for (Candle candle : candles) {
            long day = Math.floorDiv(candle.epochSeconds() + IST_OFFSET_SECONDS, SECONDS_PER_DAY);
            if (day != session) {
                session = day;
                runningMax = 0;
            }
            long cumulative = Math.max(runningMax, candle.volume());
            long own = cumulative - runningMax;
            runningMax = cumulative;
            result.add(new Candle(candle.epochSeconds(), candle.open(), candle.high(),
                    candle.low(), candle.close(), own));
        }
        return result;
    }
}
