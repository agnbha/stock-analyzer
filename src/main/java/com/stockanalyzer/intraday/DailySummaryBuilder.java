package com.stockanalyzer.intraday;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.DailyGainSummary;
import com.stockanalyzer.model.GainOpportunity;
import com.stockanalyzer.model.TradingSession;

import java.util.List;

/** Turns one session's candles plus its detected opportunities into the row that gets stored. */
public final class DailySummaryBuilder {

    private final GainOpportunityDetector detector;

    public DailySummaryBuilder(GainOpportunityDetector detector) {
        this.detector = detector;
    }

    public DailyGainSummary build(TradingSession session, Double previousClose) {
        List<Candle> candles = session.candles();
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("Cannot summarise a session with no candles: " + session.symbol());
        }

        double high = Double.MIN_VALUE;
        double low = Double.MAX_VALUE;
        long volume = 0;
        for (Candle candle : candles) {
            high = Math.max(high, candle.high());
            low = Math.min(low, candle.low());
            volume += candle.volume();
        }
        Candle first = candles.getFirst();
        Candle last = candles.getLast();

        List<GainOpportunity> opportunities = detector.detect(candles);
        Double dayChangePct = previousClose == null || previousClose <= 0
                ? null
                : (last.close() - previousClose) / previousClose * 100.0;

        return new DailyGainSummary(session.symbol(), session.sessionDate(), session.intervalMinutes(),
                first.open(), high, low, last.close(), volume, dayChangePct, candles.size(),
                first.epochSeconds(), last.epochSeconds(), opportunities);
    }

    public String detectorVersion() {
        return detector.version();
    }
}
