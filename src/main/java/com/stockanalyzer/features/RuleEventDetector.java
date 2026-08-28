package com.stockanalyzer.features;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.EventType;
import com.stockanalyzer.model.MarketEvent;
import com.stockanalyzer.model.TradingSession;
import com.stockanalyzer.util.MarketClock;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The rule-based event vocabulary. Each rule fires at most once per session per
 * type, so an alert says "the opening range broke" rather than repeating it for
 * every candle that stays above the line.
 */
public final class RuleEventDetector implements EventDetector {

    private final MarketClock clock;
    private final int openingRangeMinutes;
    private final double volumeSpikeZ;
    private final double gapPct;
    private final int reversalCandles;

    public RuleEventDetector(MarketClock clock) {
        this(clock, 15, 3.0, 1.0, 4);
    }

    public RuleEventDetector(MarketClock clock, int openingRangeMinutes, double volumeSpikeZ,
                             double gapPct, int reversalCandles) {
        this.clock = clock;
        this.openingRangeMinutes = openingRangeMinutes;
        this.volumeSpikeZ = volumeSpikeZ;
        this.gapPct = gapPct;
        this.reversalCandles = reversalCandles;
    }

    @Override
    public String version() {
        return "rules/v1";
    }

    @Override
    public List<MarketEvent> detect(TradingSession session, PriorSessionContext context) {
        List<Candle> candles = session.candles();
        if (candles.size() < 2) {
            return List.of();
        }

        List<MarketEvent> events = new ArrayList<>();
        Set<EventType> alreadyFired = EnumSet.noneOf(EventType.class);
        double[] vwap = VwapCalculator.running(candles);

        double openingHigh = Double.MIN_VALUE;
        double openingLow = Double.MAX_VALUE;
        double firstHourHigh = Double.MIN_VALUE;
        int openingRangeEnd = -1;
        for (int i = 0; i < candles.size(); i++) {
            int minute = clock.minutesSinceOpen(candles.get(i).epochSeconds());
            if (minute < openingRangeMinutes) {
                openingHigh = Math.max(openingHigh, candles.get(i).high());
                openingLow = Math.min(openingLow, candles.get(i).low());
                openingRangeEnd = i;
            }
            if (minute < 60) {
                firstHourHigh = Math.max(firstHourHigh, candles.get(i).high());
            }
        }

        Candle open = candles.getFirst();
        if (context.priorClose() != null && context.priorClose() > 0) {
            double gap = (open.open() - context.priorClose()) / context.priorClose() * 100.0;
            if (Math.abs(gap) >= gapPct) {
                events.add(event(session, open, EventType.GAP_AND_GO, gap));
                alreadyFired.add(EventType.GAP_AND_GO);
            }
        }

        int consecutiveDown = 0;
        for (int i = 1; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            Candle previous = candles.get(i - 1);
            int minute = clock.minutesSinceOpen(candle.epochSeconds());

            if (i > openingRangeEnd && openingRangeEnd >= 0 && openingHigh > Double.MIN_VALUE
                    && candle.high() > openingHigh && alreadyFired.add(EventType.OPENING_RANGE_BREAKOUT)) {
                events.add(event(session, candle, EventType.OPENING_RANGE_BREAKOUT,
                        pct(candle.high(), openingHigh)));
            }
            if (previous.close() < vwap[i - 1] && candle.close() > vwap[i]
                    && alreadyFired.add(EventType.VWAP_RECLAIM)) {
                events.add(event(session, candle, EventType.VWAP_RECLAIM, pct(candle.close(), vwap[i])));
            }
            if (previous.close() > vwap[i - 1] && candle.close() < vwap[i]
                    && alreadyFired.add(EventType.VWAP_LOSS)) {
                events.add(event(session, candle, EventType.VWAP_LOSS, pct(candle.close(), vwap[i])));
            }
            double z = context.volumeProfile().zScore(minute, candle.volume());
            if (z >= volumeSpikeZ && alreadyFired.add(EventType.VOLUME_SPIKE)) {
                events.add(event(session, candle, EventType.VOLUME_SPIKE, z));
            }
            if (context.priorHigh() != null && candle.high() > context.priorHigh()
                    && alreadyFired.add(EventType.PRIOR_DAY_HIGH_BREAK)) {
                events.add(event(session, candle, EventType.PRIOR_DAY_HIGH_BREAK,
                        pct(candle.high(), context.priorHigh())));
            }
            if (minute >= 60 && firstHourHigh > Double.MIN_VALUE && candle.high() > firstHourHigh
                    && alreadyFired.add(EventType.FIRST_HOUR_HIGH_BREAK)) {
                events.add(event(session, candle, EventType.FIRST_HOUR_HIGH_BREAK,
                        pct(candle.high(), firstHourHigh)));
            }

            if (candle.close() < candle.open()) {
                consecutiveDown++;
            } else {
                if (consecutiveDown >= reversalCandles && alreadyFired.add(EventType.REVERSAL_AFTER_DECLINE)) {
                    events.add(event(session, candle, EventType.REVERSAL_AFTER_DECLINE, consecutiveDown));
                }
                consecutiveDown = 0;
            }
        }
        return events;
    }

    private static double pct(double value, double reference) {
        return reference == 0 ? 0 : (value - reference) / reference * 100.0;
    }

    private static MarketEvent event(TradingSession session, Candle candle, EventType type, double strength) {
        return new MarketEvent(session.symbol(), session.sessionDate(), candle.epochSeconds(), type, strength);
    }
}
