package com.stockanalyzer.trade;

import com.stockanalyzer.model.PeriodType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeriodBoundsTest {

    private final PeriodBounds bounds = new PeriodBounds(4);

    @Test
    @DisplayName("weeks are ISO weeks starting Monday")
    void weeksStartOnMonday() {
        LocalDate thursday = LocalDate.of(2026, 8, 27);

        assertEquals(LocalDate.of(2026, 8, 24), bounds.startOf(PeriodType.WEEK, thursday));
        assertEquals(LocalDate.of(2026, 8, 30), bounds.endOf(PeriodType.WEEK, thursday));
    }

    @Test
    @DisplayName("months run to the last day, leap years included")
    void monthsCoverTheWholeMonth() {
        assertEquals(LocalDate.of(2026, 2, 1), bounds.startOf(PeriodType.MONTH, LocalDate.of(2026, 2, 14)));
        assertEquals(LocalDate.of(2026, 2, 28), bounds.endOf(PeriodType.MONTH, LocalDate.of(2026, 2, 14)));
        assertEquals(LocalDate.of(2028, 2, 29), bounds.endOf(PeriodType.MONTH, LocalDate.of(2028, 2, 1)));
    }

    @Test
    @DisplayName("the financial year runs April to March")
    void financialYearRunsAprilToMarch() {
        assertEquals(LocalDate.of(2026, 4, 1), bounds.startOf(PeriodType.FY, LocalDate.of(2026, 8, 27)));
        assertEquals(LocalDate.of(2027, 3, 31), bounds.endOf(PeriodType.FY, LocalDate.of(2026, 8, 27)));

        // A January date belongs to the financial year that began the previous April.
        assertEquals(LocalDate.of(2025, 4, 1), bounds.startOf(PeriodType.FY, LocalDate.of(2026, 1, 15)));
        assertEquals("2025-26", bounds.label(PeriodType.FY, LocalDate.of(2026, 1, 15)));
    }
}
