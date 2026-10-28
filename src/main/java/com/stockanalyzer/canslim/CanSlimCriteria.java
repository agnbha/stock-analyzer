package com.stockanalyzer.canslim;

/**
 * Every threshold the CAN SLIM rules use, in one place.
 *
 * <p>The defaults are O'Neil's own numbers where he gives one (25% earnings
 * growth, 17% ROE, an RS rating of 80, a 40-50% volume surge on the breakout,
 * buying within 5% of the pivot, cutting losses at 7-8%) and conservative
 * readings of his prose where he does not.
 *
 * @param minQtrProfitGrowthPct   C: latest quarter's profit vs the same quarter a year ago
 * @param minQtrSalesGrowthPct    C: and sales, so the growth is not one-off cost cutting
 * @param minProfitGrowth3yPct    A: compounded annual profit growth over three years
 * @param minRoePct               A: return on equity
 * @param maxPctFromHigh          N: how far below its 52-week high a stock may be
 * @param minUpDownVolumeRatio    S: volume on up days over volume on down days, 50 sessions
 * @param minRsRating             L: relative strength percentile, 1-99
 * @param minBaseSessions         shortest consolidation that counts as a base (5 weeks)
 * @param maxBaseSessions         longest look back for a base's pivot (65 weeks)
 * @param maxBaseDepthPct         deeper corrections are broken stocks, not bases
 * @param minPriorAdvancePct      the uptrend a base must follow; unknown history does not disqualify
 * @param breakoutVolumeRatio     breakout-day volume over the 50-day average
 * @param buyRangePct             how far above the pivot a breakout is still buyable
 * @param buyWindowSessions       how many sessions after a breakout it still counts as fresh
 * @param watchWithinPct          how close below the pivot a setup is flagged as about to break out
 * @param breakoutLookbackSessions how far back a breakout is remembered for the sell rules
 * @param stopLossPct             the loss below the buy point that ends the trade
 * @param heavyVolumeRatio        what counts as heavy volume on a break of the 50-day average
 * @param climaxRunPct            a gain this large inside {@link #CLIMAX_WINDOW} sessions is a climax run
 * @param climaxMinGainPct        a climax only matters once the stock is this far above its buy point
 * @param rallyMinPct             the advance off a low that counts as "had a rally" for the sell rules
 * @param distributionDropPct     an index decline at least this large on rising volume is a distribution day
 * @param distributionUnderPressure distribution days in 25 sessions that put the uptrend under pressure
 * @param distributionCorrection  distribution days in 25 sessions that end it
 */
public record CanSlimCriteria(
        double minQtrProfitGrowthPct,
        double minQtrSalesGrowthPct,
        double minProfitGrowth3yPct,
        double minRoePct,
        double maxPctFromHigh,
        double minUpDownVolumeRatio,
        int minRsRating,
        int minBaseSessions,
        int maxBaseSessions,
        double maxBaseDepthPct,
        double minPriorAdvancePct,
        double breakoutVolumeRatio,
        double buyRangePct,
        int buyWindowSessions,
        double watchWithinPct,
        int breakoutLookbackSessions,
        double stopLossPct,
        double heavyVolumeRatio,
        double climaxRunPct,
        double climaxMinGainPct,
        double rallyMinPct,
        double distributionDropPct,
        int distributionUnderPressure,
        int distributionCorrection) {

    /** Sessions over which a climax run is measured: roughly three weeks. */
    public static final int CLIMAX_WINDOW = 15;
    /** Sessions over which distribution days are counted: roughly five weeks. */
    public static final int DISTRIBUTION_WINDOW = 25;

    public static CanSlimCriteria defaults() {
        return new CanSlimCriteria(25, 20, 25, 17, 15, 1.0, 80,
                25, 325, 35, 20, 1.4, 5, 5, 5, 60,
                8, 1.4, 25, 20, 25,
                0.2, 4, 6);
    }
}
