package com.stockanalyzer.canslim;

/**
 * What the rules say to do about a stock today. Sell signals outrank buy
 * signals: when both apply, protecting capital comes first.
 *
 * <p>These are statistics about price and volume against O'Neil's published
 * rules, not advice. Nothing here places an order.
 */
public enum CanSlimSignal {
    /** Cut it: the close is 7-8% (configurable) below the buy point. */
    SELL_STOP_LOSS(true),
    /** The rally has ended: a heavy-volume close below the 50-day average. */
    SELL_TREND_BREAK(true),
    /** The rally is exhausting itself: a climax run, or the biggest up day since the breakout on the heaviest volume. */
    SELL_CLIMAX(true),
    /** Broke out of a base today, on volume, inside the buy range. */
    BUY_BREAKOUT(false),
    /** Broke out in the last few sessions and is still inside the buy range. */
    BUY_ZONE(false),
    /** Within a few percent under its pivot in a sound base: about to break out, or not. */
    WATCH_SETUP(false),
    /** Broke out and is now beyond the buy range - chasing it raises the risk. */
    EXTENDED(false),
    /** Would be a buy or a setup, but the market is in correction. */
    AVOID_MARKET(false),
    NONE(false);

    private final boolean sell;

    CanSlimSignal(boolean sell) {
        this.sell = sell;
    }

    public boolean isSell() {
        return sell;
    }
}
