package com.stockanalyzer.model;

import java.time.LocalDate;
import java.util.List;

/**
 * One instrument's candles for one trading session. Candles are time-ordered
 * and all fall on {@code sessionDate} in the exchange's local timezone.
 */
public record TradingSession(String symbol,
                             String exchange,
                             String segment,
                             LocalDate sessionDate,
                             int intervalMinutes,
                             List<Candle> candles) {
}
