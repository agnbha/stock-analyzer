package com.stockanalyzer.intraday;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import com.stockanalyzer.store.CalendarRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHolidaySourceTest {

    @Test
    @DisplayName("the shipped holiday file parses, comment lines and all")
    void shippedFileParses() {
        FileHolidaySource source = FileHolidaySource.fromClasspath("nse-holidays-2026.txt");

        assertTrue(source.holidays().contains(LocalDate.of(2026, 1, 26)), "Republic Day");
        assertTrue(source.holidays().contains(LocalDate.of(2026, 10, 2)), "Gandhi Jayanti");
        assertEquals(LocalDate.of(2026, 12, 25), source.coveredUntil().orElseThrow());
    }

    @Test
    @DisplayName("the festival holidays that move every year are in the shipped list, not just the fixed national ones")
    void shippedFileHasTheFestivalHolidays() {
        FileHolidaySource source = FileHolidaySource.fromClasspath("nse-holidays-2026.txt");

        assertTrue(source.holidays().contains(LocalDate.of(2026, 3, 3)), "Holi");
        assertTrue(source.holidays().contains(LocalDate.of(2026, 10, 20)), "Dussehra");
        assertTrue(source.holidays().contains(LocalDate.of(2026, 11, 10)), "Diwali-Balipratipada");
        assertTrue(source.holidays().contains(LocalDate.of(2026, 11, 24)), "Guru Nanak Jayanti");
        assertEquals(20, source.holidays().size(), "NSE lists twenty equity holidays for 2026");
    }

    @Test
    @DisplayName("the next weekday holiday is not a trading day, so the daemon stays out of it")
    void calendarSkipsTheNextHoliday() {
        FileHolidaySource source = FileHolidaySource.fromClasspath("nse-holidays-2026.txt");
        DefaultTradingCalendar calendar = new DefaultTradingCalendar(source, new CalendarRepository() {
            @Override
            public void markNonTrading(LocalDate date, String reason) {
            }

            @Override
            public java.util.Set<LocalDate> nonTradingDays(LocalDate from, LocalDate to) {
                return java.util.Set.of();
            }

            @Override
            public java.util.Set<LocalDate> datesWithTradingEvidence(LocalDate from, LocalDate to) {
                return java.util.Set.of();
            }
        });

        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 11, 10)), "Diwali-Balipratipada, a Tuesday");
        assertTrue(calendar.isTradingDay(LocalDate.of(2026, 11, 9)), "the Monday before it");
        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 11, 8)), "a Sunday");
    }

    @Test
    @DisplayName("a missing file degrades to weekends only rather than failing startup")
    void missingFileIsNotFatal() {
        FileHolidaySource source = FileHolidaySource.fromClasspath("no-such-holidays.txt");

        assertTrue(source.holidays().isEmpty());
        assertTrue(source.coveredUntil().isEmpty());
    }
}
