package com.stockanalyzer.live;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.MarketEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the monitor has seen for one symbol today, in memory.
 *
 * <p>The newest candle is usually still forming - its high, low and close will
 * all change before the interval ends. It is kept here and marked provisional,
 * and {@link #completedCandles(long)} is what any analysis or write runs on.
 * Provisional data never reaches the canonical tables.
 */
public final class LiveSessionState {

    private final String symbol;
    private final int intervalMinutes;
    private final Map<Long, Candle> candlesByTimestamp = new LinkedHashMap<>();
    private final List<MarketEvent> events = new ArrayList<>();

    public LiveSessionState(String symbol, int intervalMinutes) {
        this.symbol = symbol;
        this.intervalMinutes = intervalMinutes;
    }

    public String symbol() {
        return symbol;
    }

    /** Later fetches overwrite earlier ones for the same timestamp - that is how a forming candle settles. */
    public synchronized void merge(List<Candle> fetched) {
        for (Candle candle : fetched) {
            candlesByTimestamp.put(candle.epochSeconds(), candle);
        }
    }

    public synchronized void addEvents(List<MarketEvent> newEvents) {
        events.addAll(newEvents);
    }

    public synchronized List<MarketEvent> events() {
        return List.copyOf(events);
    }

    public synchronized List<Candle> allCandles() {
        return candlesByTimestamp.values().stream()
                .sorted((a, b) -> Long.compare(a.epochSeconds(), b.epochSeconds()))
                .toList();
    }

    /** Everything whose interval has certainly closed by {@code nowEpoch}. */
    public synchronized List<Candle> completedCandles(long nowEpoch) {
        return allCandles().stream()
                .filter(candle -> !isProvisional(candle, nowEpoch))
                .toList();
    }

    public synchronized boolean lastCandleProvisional(long nowEpoch) {
        List<Candle> all = allCandles();
        return !all.isEmpty() && isProvisional(all.getLast(), nowEpoch);
    }

    public synchronized long watermark() {
        List<Candle> all = allCandles();
        return all.isEmpty() ? 0 : all.getLast().epochSeconds();
    }

    public synchronized double lastPrice() {
        List<Candle> all = allCandles();
        return all.isEmpty() ? 0 : all.getLast().close();
    }

    private boolean isProvisional(Candle candle, long nowEpoch) {
        return candle.epochSeconds() + intervalMinutes * 60L > nowEpoch;
    }
}
