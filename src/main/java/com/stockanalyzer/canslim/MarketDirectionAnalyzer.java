package com.stockanalyzer.canslim;

import java.util.List;
import java.util.Locale;

/**
 * Reads the market's direction from an index proxy's daily bars.
 *
 * <p>Two of O'Neil's tests: where the index sits against its 50-day (and, with
 * enough history, 200-day) average, and how many distribution days - a decline
 * on volume heavier than the previous session's - it has taken in the last five
 * weeks. Four or five mean institutions are selling into strength; more mean the
 * uptrend is over.
 *
 * <p>The proxy is an index ETF because the candle feed prices tradeable
 * symbols, not the index itself. Its volume tracks the index's closely but not
 * exactly, so distribution counts are an approximation. O'Neil also retires a
 * distribution day once the index has rallied 5% past it; that refinement is
 * not modelled, which errs towards caution.
 */
public final class MarketDirectionAnalyzer {

    private final CanSlimCriteria criteria;

    public MarketDirectionAnalyzer(CanSlimCriteria criteria) {
        this.criteria = criteria;
    }

    public MarketAssessment assess(String indexSymbol, List<DailyBar> bars) {
        if (bars.size() < 50) {
            return MarketAssessment.unknown(indexSymbol, String.format(Locale.ROOT,
                    "only %d sessions of %s history; the 50-day average needs 50", bars.size(), indexSymbol));
        }
        int t = bars.size() - 1;
        double close = bars.get(t).close();
        double sma50 = Bars.sma(bars, t, 50);
        Double sma200 = Bars.sma(bars, t, 200);
        Double sma50TenDaysAgo = Bars.sma(bars, t - 10, 50);
        boolean fiftyDayFalling = sma50TenDaysAgo != null && sma50 < sma50TenDaysAgo;
        int distribution = distributionDays(bars);

        MarketState state;
        if ((close < sma50 && fiftyDayFalling) || distribution >= criteria.distributionCorrection()) {
            state = MarketState.CORRECTION;
        } else if (close < sma50 || distribution >= criteria.distributionUnderPressure()
                || (sma200 != null && close < sma200)) {
            state = MarketState.UPTREND_UNDER_PRESSURE;
        } else {
            state = MarketState.CONFIRMED_UPTREND;
        }

        String note = String.format(Locale.ROOT,
                "%s %.2f vs 50-day %.2f (%s)%s; %d distribution day(s) in the last %d sessions",
                indexSymbol, close, sma50, fiftyDayFalling ? "falling" : "rising",
                sma200 == null ? "" : String.format(Locale.ROOT, ", 200-day %.2f", sma200),
                distribution, CanSlimCriteria.DISTRIBUTION_WINDOW);
        return new MarketAssessment(indexSymbol, bars.get(t).date(), state, close, sma50, sma200,
                distribution, note);
    }

    int distributionDays(List<DailyBar> bars) {
        int t = bars.size() - 1;
        int count = 0;
        for (int i = Math.max(1, t - CanSlimCriteria.DISTRIBUTION_WINDOW + 1); i <= t; i++) {
            DailyBar today = bars.get(i);
            DailyBar yesterday = bars.get(i - 1);
            boolean declined = Bars.pctChange(yesterday.close(), today.close()) <= -criteria.distributionDropPct();
            if (declined && today.volume() > yesterday.volume()) {
                count++;
            }
        }
        return count;
    }
}
