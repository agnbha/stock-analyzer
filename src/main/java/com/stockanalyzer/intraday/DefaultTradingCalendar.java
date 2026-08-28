package com.stockanalyzer.intraday;

import com.stockanalyzer.store.CalendarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Weekends, then the published holiday list, then holidays previously inferred
 * from days on which no symbol returned any data.
 */
public final class DefaultTradingCalendar implements TradingCalendar {

    private static final Logger log = LoggerFactory.getLogger(DefaultTradingCalendar.class);
    private static final int COVERAGE_WARNING_DAYS = 30;

    private final HolidaySource holidaySource;
    private final CalendarRepository calendarRepository;

    public DefaultTradingCalendar(HolidaySource holidaySource, CalendarRepository calendarRepository) {
        this.holidaySource = holidaySource;
        this.calendarRepository = calendarRepository;
        warnIfHolidayListRunningOut();
    }

    private void warnIfHolidayListRunningOut() {
        holidaySource.coveredUntil().ifPresentOrElse(last -> {
            if (last.isBefore(LocalDate.now().plusDays(COVERAGE_WARNING_DAYS))) {
                log.warn("Holiday list only covers to {}. Add next year's dates before scheduled alerts "
                        + "start firing on holidays.", last);
            }
        }, () -> log.warn("No exchange holidays configured; only weekends will be skipped"));
    }

    @Override
    public boolean isTradingDay(LocalDate date) {
        if (isWeekend(date) || holidaySource.holidays().contains(date)) {
            return false;
        }
        return !calendarRepository.nonTradingDays(date, date).contains(date);
    }

    @Override
    public List<LocalDate> tradingDaysBetween(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return List.of();
        }
        Set<LocalDate> inferred = calendarRepository.nonTradingDays(from, to);
        Set<LocalDate> published = holidaySource.holidays();
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!isWeekend(date) && !published.contains(date) && !inferred.contains(date)) {
                days.add(date);
            }
        }
        return days;
    }

    @Override
    public LocalDate previousTradingDay(LocalDate date) {
        LocalDate candidate = date.minusDays(1);
        // A run of holidays longer than a fortnight would mean the calendar is wrong, not the market closed.
        for (int guard = 0; guard < 14; guard++) {
            if (isTradingDay(candidate)) {
                return candidate;
            }
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
