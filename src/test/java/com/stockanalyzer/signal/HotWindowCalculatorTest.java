package com.stockanalyzer.signal;

import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.model.HotWindow;
import com.stockanalyzer.store.OpportunityRow;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotWindowCalculatorTest {

    private final MarketClock clock = MarketClock.nse();
    private final HotWindowCalculator calculator = new HotWindowCalculator(clock, 5);

    @Test
    @DisplayName("the bound discounts an estimate by how little evidence supports it")
    void wilsonBoundShrinksThinEvidence() {
        double twoOfTwo = HotWindowCalculator.wilsonLowerBound(2, 2);
        double fortyOfHundred = HotWindowCalculator.wilsonLowerBound(40, 100);

        // A perfect 2-of-2 is discounted from 1.00 to about a third; 40-of-100
        // barely moves from 0.40. The bound never claims certainty.
        assertTrue(twoOfTwo < 0.4, "2/2 was " + twoOfTwo);
        assertTrue(fortyOfHundred > 0.3 && fortyOfHundred < 0.40, "40/100 was " + fortyOfHundred);
        assertTrue(1.0 - fortyOfHundred / 0.40 < 1.0 - twoOfTwo,
                "the small sample is penalised far harder in relative terms");
        assertEquals(0.0, HotWindowCalculator.wilsonLowerBound(0, 0), 0.0001);
    }

    @Test
    @DisplayName("more of the same evidence tightens the bound toward the true rate")
    void moreEvidenceTightensTheBound() {
        double tenOfTwenty = HotWindowCalculator.wilsonLowerBound(10, 20);
        double hundredOfTwoHundred = HotWindowCalculator.wilsonLowerBound(100, 200);

        assertTrue(hundredOfTwoHundred > tenOfTwenty,
                "same 50% rate, ten times the evidence: " + hundredOfTwoHundred + " vs " + tenOfTwenty);
        assertTrue(hundredOfTwoHundred < 0.5, "still below the point estimate");
    }

    @Test
    @DisplayName("entries are bucketed by minutes since the open")
    void bucketsByTimeOfDay() {
        List<OpportunityRow> rows = new ArrayList<>();
        // Three sessions, all with an entry at 09:47 - 32 minutes after the open, so bucket 30.
        for (int day = 0; day < 3; day++) {
            rows.add(row("RELIANCE", LocalDate.of(2026, 8, 24).plusDays(day), LocalTime.of(9, 47), 2.0));
        }
        rows.add(row("RELIANCE", LocalDate.of(2026, 8, 24), LocalTime.of(13, 5), 0.8));

        List<HotWindow> windows = calculator.compute(rows, 60);

        HotWindow morning = windows.stream()
                .filter(w -> "RELIANCE".equals(w.symbol()) && w.bucketStartMinute() == 30)
                .findFirst().orElseThrow();
        assertEquals(3, morning.hits());
        assertEquals(3, morning.sessions());
        assertEquals(2.0, morning.medianGainPct(), 0.0001);
        assertTrue(windows.stream().anyMatch(w -> w.symbol() == null), "market-wide windows are computed too");
    }

    private OpportunityRow row(String symbol, LocalDate date, LocalTime time, double gainPct) {
        long entryTs = ZonedDateTime.of(date, time, clock.zone()).toEpochSecond();
        return new OpportunityRow(0, symbol, date,
                new GainOpportunity(1, entryTs, entryTs + 1800, 100, 100 + gainPct, gainPct, 30));
    }
}
