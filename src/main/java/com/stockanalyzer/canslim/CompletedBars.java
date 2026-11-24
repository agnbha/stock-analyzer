package com.stockanalyzer.canslim;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.util.MarketClock;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Daily candles as bars: one a session, oldest first. */
public final class CompletedBars {

    private CompletedBars() {
    }

    /** Without today's while the market is open: a half-formed day would read as a volume collapse, or a false high. */
    public static List<DailyBar> of(List<Candle> candles, MarketClock marketClock, Clock clock) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(marketClock.zone()));
        LocalDate today = now.toLocalDate();
        boolean sessionOver = !now.toLocalTime().isBefore(marketClock.sessionClose());

        TreeMap<LocalDate, DailyBar> byDate = new TreeMap<>();
        for (Candle candle : candles) {
            LocalDate date = marketClock.sessionDateOf(candle.epochSeconds());
            if (date.equals(today) && !sessionOver) {
                continue;
            }
            byDate.put(date, new DailyBar(date, candle.open(), candle.high(), candle.low(), candle.close(),
                    candle.volume()));
        }
        return new ArrayList<>(byDate.values());
    }
}
