package com.stockanalyzer.intraday;

import java.time.LocalDate;
import java.util.List;

public interface TradingCalendar {

    boolean isTradingDay(LocalDate date);

    /** Every trading day in the inclusive range - the {@code wanted} half of the backfill set difference. */
    List<LocalDate> tradingDaysBetween(LocalDate from, LocalDate to);

    /** The most recent trading day strictly before {@code date}. */
    LocalDate previousTradingDay(LocalDate date);
}
