package com.stockanalyzer.intraday;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHolidaySourceTest {

    @Test
    @DisplayName("the shipped holiday file parses, comment lines and all")
    void shippedFileParses() {
        FileHolidaySource source = FileHolidaySource.fromClasspath("nse-holidays-2026.txt");

        assertTrue(source.holidays().contains(LocalDate.of(2026, 1, 26)), "Republic Day");
        assertTrue(source.holidays().contains(LocalDate.of(2026, 10, 2)), "Gandhi Jayanti");
        assertEquals(LocalDate.of(2026, 10, 2), source.coveredUntil().orElseThrow());
    }

    @Test
    @DisplayName("a missing file degrades to weekends only rather than failing startup")
    void missingFileIsNotFatal() {
        FileHolidaySource source = FileHolidaySource.fromClasspath("no-such-holidays.txt");

        assertTrue(source.holidays().isEmpty());
        assertTrue(source.coveredUntil().isEmpty());
    }
}
