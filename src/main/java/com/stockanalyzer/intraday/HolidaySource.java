package com.stockanalyzer.intraday;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * Published exchange holidays. Alerts and schedules look forward, and holidays
 * inferred from empty data only work backwards - so the forward-looking list has
 * to come from somewhere it can be maintained by hand.
 */
public interface HolidaySource {

    Set<LocalDate> holidays();

    /** Latest date the list covers; used to warn before the file runs out. */
    Optional<LocalDate> coveredUntil();
}
