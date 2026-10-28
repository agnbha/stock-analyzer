package com.stockanalyzer.canslim;

import com.stockanalyzer.store.FundamentalsCacheRepository.Cached;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundamentalsRefreshPolicyTest {

    private static final long DAY = 86_400L;
    private static final Map<String, Double> RATIOS = Map.of(ScreenerRatios.QTR_PROFIT_GROWTH, 30.0);

    private final FundamentalsRefreshPolicy policy = new FundamentalsRefreshPolicy(7, 2);

    private static long epoch(LocalDate date) {
        return date.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    }

    /** A copy fetched {@code ageDays} before {@code today}. */
    private static Cached copy(LocalDate today, int ageDays, YearMonth results, YearMonth holding) {
        return new Cached(epoch(today) - ageDays * DAY, new CompanyFundamentals(RATIOS, results, holding));
    }

    private boolean due(Cached cached, LocalDate today) {
        return policy.due(cached, today, epoch(today));
    }

    private static final YearMonth MAR = YearMonth.of(2025, 3);
    private static final YearMonth JUN = YearMonth.of(2025, 6);

    @Test
    @DisplayName("a copy showing the latest ended quarter is left alone, however old, until the next quarter ends")
    void currentCopyIsFrozen() {
        assertFalse(due(copy(LocalDate.of(2025, 8, 5), 30, JUN, JUN), LocalDate.of(2025, 8, 5)));
        assertFalse(due(copy(LocalDate.of(2025, 9, 29), 90, JUN, JUN), LocalDate.of(2025, 9, 29)));
        assertFalse(due(copy(LocalDate.of(2025, 10, 7), 120, JUN, JUN), LocalDate.of(2025, 10, 7)),
                "September ended a week ago: nobody has reported it yet");
    }

    @Test
    @DisplayName("a company yet to report is checked every two days once the season is open, not before")
    void seasonCheckCadence() {
        LocalDate today = LocalDate.of(2025, 8, 1);
        assertFalse(due(copy(today, 1, MAR, JUN), today), "looked yesterday");
        assertTrue(due(copy(today, 2, MAR, JUN), today), "two days ago is due");

        LocalDate tooEarly = LocalDate.of(2025, 7, 5);
        assertFalse(due(copy(tooEarly, 30, MAR, JUN), tooEarly), "five days after June ended nobody has reported");
        LocalDate firstDay = LocalDate.of(2025, 7, 8);
        assertTrue(due(copy(firstDay, 30, MAR, JUN), firstDay), "the eighth day opens the season");
    }

    @Test
    @DisplayName("results in but the shareholding pattern still a quarter behind keeps it under watch")
    void eitherTableBehind() {
        LocalDate today = LocalDate.of(2025, 8, 1);
        assertTrue(due(copy(today, 2, JUN, MAR), today));
        assertFalse(due(copy(today, 1, JUN, MAR), today));
    }

    @Test
    @DisplayName("a copy more than a quarter behind is refreshed at once, season or not")
    void wellBehind() {
        LocalDate today = LocalDate.of(2025, 7, 3);
        assertTrue(due(copy(today, 1, YearMonth.of(2024, 12), JUN.minusMonths(6)), today));
    }

    @Test
    @DisplayName("past the filing deadline a company still missing the quarter drops to the slow pace")
    void pastTheDeadline() {
        LocalDate today = LocalDate.of(2025, 9, 5);
        assertFalse(due(copy(today, 3, MAR, JUN), today));
        assertTrue(due(copy(today, 7, MAR, JUN), today));
    }

    @Test
    @DisplayName("a page that had no figures (an ETF) is retried at the slow pace whatever the season")
    void emptyPage() {
        LocalDate today = LocalDate.of(2025, 8, 1);
        Cached none = new Cached(epoch(today) - 6 * DAY, CompanyFundamentals.NONE);
        assertFalse(due(none, today));
        assertTrue(due(new Cached(epoch(today) - 7 * DAY, CompanyFundamentals.NONE), today));
    }

    @Test
    @DisplayName("figures stored before quarters were recorded are read once more to learn them")
    void legacyRow() {
        LocalDate today = LocalDate.of(2025, 8, 1);
        assertTrue(due(copy(today, 0, null, null), today));
    }

    @Test
    @DisplayName("zero days means every page is read every time")
    void zeroDisablesTheCache() {
        LocalDate today = LocalDate.of(2025, 8, 1);
        assertTrue(new FundamentalsRefreshPolicy(0, 2).due(copy(today, 0, JUN, JUN), today, epoch(today)));
    }

    @Test
    @DisplayName("the expected quarter is the newest one that has ended")
    void expectedQuarter() {
        assertEquals(JUN, FundamentalsRefreshPolicy.expectedQuarter(LocalDate.of(2025, 9, 30)));
        assertEquals(YearMonth.of(2025, 9), FundamentalsRefreshPolicy.expectedQuarter(LocalDate.of(2025, 10, 1)));
        assertEquals(YearMonth.of(2025, 9), FundamentalsRefreshPolicy.expectedQuarter(LocalDate.of(2025, 12, 31)));
        assertEquals(YearMonth.of(2025, 12), FundamentalsRefreshPolicy.expectedQuarter(LocalDate.of(2026, 1, 1)));
        assertEquals(YearMonth.of(2025, 12), FundamentalsRefreshPolicy.expectedQuarter(LocalDate.of(2026, 3, 31)));
        assertEquals(YearMonth.of(2026, 3), FundamentalsRefreshPolicy.expectedQuarter(LocalDate.of(2026, 4, 1)));
    }

    @Test
    @DisplayName("a company counts as awaiting results only once they could have arrived")
    void awaitingResults() {
        CompanyFundamentals behind = new CompanyFundamentals(RATIOS, MAR, JUN);
        assertTrue(FundamentalsRefreshPolicy.awaitingResults(behind, LocalDate.of(2025, 8, 1)));
        assertFalse(FundamentalsRefreshPolicy.awaitingResults(behind, LocalDate.of(2025, 7, 5)));
        assertFalse(FundamentalsRefreshPolicy.awaitingResults(
                new CompanyFundamentals(RATIOS, JUN, JUN), LocalDate.of(2025, 8, 1)));
    }
}
