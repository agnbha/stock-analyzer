package com.stockanalyzer.canslim;

import com.stockanalyzer.store.FundamentalsCacheRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Decides when a company page is worth reading again.
 *
 * <p>The figures CAN SLIM reads move when a company reports a quarter, so the
 * calendar says far more than the age of the copy does:
 * <ul>
 *   <li>once a copy shows the latest quarter that has ended, nothing on the page
 *       can change until the next quarter ends - it is left alone;</li>
 *   <li>a copy still showing an earlier quarter is checked every
 *       {@code seasonCheckDays}, but only while results can be arriving: from
 *       {@value #FIRST_REPORTING_DAY} days after the quarter ends (nobody has
 *       reported before then) to {@value #LAST_REPORTING_DAY} (the filing
 *       deadline for the year-end quarter; others have 45);</li>
 *   <li>past that, a company still missing the quarter is late, suspended or
 *       delisted, and is asked about at the slow {@code slowDays} pace;</li>
 *   <li>a page that had no figures (an ETF) is asked again at the slow pace.</li>
 * </ul>
 * The annual figures (three-year profit growth, ROE) change when the year-end
 * quarter's results arrive with the annual accounts, so the same rule covers them.
 *
 * <p>The deadlines are SEBI's listing rules as generally understood: 45 days
 * after a quarter for the results, 60 for the year-end, 21 for the shareholding
 * pattern.
 */
public final class FundamentalsRefreshPolicy {

    static final int FIRST_REPORTING_DAY = 8;
    static final int LAST_REPORTING_DAY = 60;

    private static final long DAY_SECONDS = 86_400L;

    private final int slowDays;
    private final int seasonCheckDays;

    /**
     * @param slowDays        the pace for pages with nothing to learn from; zero or less reads every page every time
     * @param seasonCheckDays how often to look again, in reporting season, at a company that has not yet shown the new quarter
     */
    public FundamentalsRefreshPolicy(int slowDays, int seasonCheckDays) {
        this.slowDays = slowDays;
        this.seasonCheckDays = seasonCheckDays;
    }

    public boolean due(FundamentalsCacheRepository.Cached cached, LocalDate today, long nowEpoch) {
        if (slowDays <= 0) {
            return true;
        }
        long age = nowEpoch - cached.fetchedAtEpoch();
        CompanyFundamentals held = cached.fundamentals();
        if (held.isEmpty()) {
            return age >= slowDays * DAY_SECONDS;
        }
        YearMonth stalest = stalestQuarter(held);
        if (stalest == null) {
            // Read before quarters were recorded: once more, to learn which quarter it is.
            return true;
        }
        YearMonth expected = expectedQuarter(today);
        if (!stalest.isBefore(expected)) {
            return false;
        }
        if (stalest.isBefore(expected.minusMonths(3))) {
            return true;
        }
        long sinceQuarterEnd = ChronoUnit.DAYS.between(expected.atEndOfMonth(), today);
        if (sinceQuarterEnd < FIRST_REPORTING_DAY) {
            return false;
        }
        if (sinceQuarterEnd <= LAST_REPORTING_DAY) {
            return age >= seasonCheckDays * DAY_SECONDS;
        }
        return age >= slowDays * DAY_SECONDS;
    }

    /** The newest quarter that has ended: the one a fully reported company would show. */
    public static YearMonth expectedQuarter(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        int quarterEndMonth = (month.getMonthValue() - 1) / 3 * 3 + 3;
        YearMonth thisQuarterEnd = YearMonth.of(month.getYear(), quarterEndMonth);
        return today.isAfter(thisQuarterEnd.atEndOfMonth()) ? thisQuarterEnd : thisQuarterEnd.minusMonths(3);
    }

    /** True when the copy is behind {@code expected} and enough time has passed that it could have caught up. */
    public static boolean awaitingResults(CompanyFundamentals held, LocalDate today) {
        YearMonth stalest = stalestQuarter(held);
        YearMonth expected = expectedQuarter(today);
        return stalest != null && stalest.isBefore(expected)
                && ChronoUnit.DAYS.between(expected.atEndOfMonth(), today) >= FIRST_REPORTING_DAY;
    }

    private static YearMonth stalestQuarter(CompanyFundamentals held) {
        YearMonth results = held.resultsQuarter();
        YearMonth holding = held.holdingQuarter();
        if (results == null) {
            return holding;
        }
        if (holding == null) {
            return results;
        }
        return results.isBefore(holding) ? results : holding;
    }
}
