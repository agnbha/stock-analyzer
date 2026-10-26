package com.stockanalyzer.canslim;

/**
 * The "M" of CAN SLIM: which way the general market is going. O'Neil's point is
 * that three of four stocks follow the market, so a perfect setup in a falling
 * market is still a bad bet.
 */
public enum MarketState {
    /** Index above its 50-day average and few distribution days: buy setups are actionable. */
    CONFIRMED_UPTREND,
    /** Still up, but institutions are selling into it: be selective, take profits sooner. */
    UPTREND_UNDER_PRESSURE,
    /** Below a falling 50-day average, or heavy distribution: no new buys. */
    CORRECTION,
    /** Too little index history to say. */
    UNKNOWN
}
