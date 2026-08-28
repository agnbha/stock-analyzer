package com.stockanalyzer.intraday;

import com.stockanalyzer.model.Candle;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** Builds one-minute candle series for tests, starting at 09:15 IST. */
public final class Candles {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final LocalDate DAY = LocalDate.of(2026, 8, 27);

    private Candles() {
    }

    public static long at(int minutesAfterOpen) {
        return ZonedDateTime.of(DAY, LocalTime.of(9, 15), IST).plusMinutes(minutesAfterOpen).toEpochSecond();
    }

    /** One candle per close price, with high/low spread symmetrically around it. */
    public static List<Candle> ofCloses(double spread, double... closes) {
        List<Candle> candles = new ArrayList<>(closes.length);
        for (int i = 0; i < closes.length; i++) {
            double close = closes[i];
            double open = i == 0 ? close : closes[i - 1];
            candles.add(new Candle(at(i), open, Math.max(open, close) + spread,
                    Math.min(open, close) - spread, close, 1000));
        }
        return candles;
    }

    /** Explicit high/low control, for cases where the spread matters. */
    public static Candle candle(int minute, double open, double high, double low, double close) {
        return new Candle(at(minute), open, high, low, close, 1000);
    }
}
