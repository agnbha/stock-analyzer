package com.stockanalyzer.canslim;

import java.time.LocalDate;

/** One session of daily-interval prices, dated by the exchange's calendar day. */
public record DailyBar(LocalDate date, double open, double high, double low, double close, long volume) {
}
