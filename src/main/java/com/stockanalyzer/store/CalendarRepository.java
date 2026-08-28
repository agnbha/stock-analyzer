package com.stockanalyzer.store;

import java.time.LocalDate;
import java.util.Set;

public interface CalendarRepository {

    void markNonTrading(LocalDate date, String reason);

    Set<LocalDate> nonTradingDays(LocalDate from, LocalDate to);
}
