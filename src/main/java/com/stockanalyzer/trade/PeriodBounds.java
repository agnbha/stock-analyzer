package com.stockanalyzer.trade;

import com.stockanalyzer.model.PeriodType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Start and end dates for a reporting period.
 *
 * <p>Weeks are ISO weeks starting Monday; the financial year runs from
 * {@code fyStartMonth} (April in India), which is the period that actually
 * matters when the numbers are used for anything official.
 */
public final class PeriodBounds {

    private final int fyStartMonth;

    public PeriodBounds(int fyStartMonth) {
        this.fyStartMonth = fyStartMonth;
    }

    public LocalDate startOf(PeriodType type, LocalDate anchor) {
        return switch (type) {
            case DAY -> anchor;
            case WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> anchor.withDayOfMonth(1);
            case FY -> fyStart(anchor);
        };
    }

    public LocalDate endOf(PeriodType type, LocalDate anchor) {
        return switch (type) {
            case DAY -> anchor;
            case WEEK -> startOf(type, anchor).plusDays(6);
            case MONTH -> anchor.withDayOfMonth(anchor.lengthOfMonth());
            case FY -> fyStart(anchor).plusYears(1).minusDays(1);
        };
    }

    private LocalDate fyStart(LocalDate anchor) {
        LocalDate start = LocalDate.of(anchor.getYear(), fyStartMonth, 1);
        return anchor.isBefore(start) ? start.minusYears(1) : start;
    }

    /** Renders "2026-27" for an Indian financial year, or the plain period start otherwise. */
    public String label(PeriodType type, LocalDate anchor) {
        LocalDate start = startOf(type, anchor);
        return switch (type) {
            case DAY -> start.toString();
            case WEEK -> "week of " + start;
            case MONTH -> start.getYear() + "-" + String.format("%02d", start.getMonthValue());
            case FY -> start.getYear() + "-" + String.format("%02d", (start.getYear() + 1) % 100);
        };
    }
}
