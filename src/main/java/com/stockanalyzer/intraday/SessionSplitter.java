package com.stockanalyzer.intraday;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.model.TradingSession;
import com.stockanalyzer.util.MarketClock;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a fetched candle range into per-session slices. A range request spans
 * many days; every downstream step works on exactly one session.
 */
public final class SessionSplitter {

    private final MarketClock clock;

    public SessionSplitter(MarketClock clock) {
        this.clock = clock;
    }

    public List<TradingSession> split(StockCandleSeries series, int intervalMinutes) {
        Map<LocalDate, List<Candle>> byDate = new LinkedHashMap<>();
        for (Candle candle : series.candles()) {
            byDate.computeIfAbsent(clock.sessionDateOf(candle.epochSeconds()), key -> new ArrayList<>()).add(candle);
        }
        List<TradingSession> sessions = new ArrayList<>(byDate.size());
        for (Map.Entry<LocalDate, List<Candle>> entry : byDate.entrySet()) {
            List<Candle> candles = new ArrayList<>(entry.getValue());
            candles.sort(Comparator.comparingLong(Candle::epochSeconds));
            sessions.add(new TradingSession(series.symbol(), series.exchange(), series.segment(),
                    entry.getKey(), intervalMinutes, List.copyOf(candles)));
        }
        sessions.sort(Comparator.comparing(TradingSession::sessionDate));
        return sessions;
    }
}
