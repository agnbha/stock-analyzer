package com.stockanalyzer.client;

import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.StockCandleSeries;
import com.stockanalyzer.model.StockFundamentals;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * {@link FundamentalsClient} derived entirely from historical daily candles
 * ({@link CandleDataClient}, {@code GET /v1/historical/candle/range}).
 *
 * <p><b>Needs only historical data access</b> — no "Live Data" entitlement
 * required, unlike {@link GrowwFundamentalsClient}. Use this when the Groww
 * API key doesn't have live-data enabled.
 *
 * <p>Trade-off: {@code marketCap}, {@code upperCircuitLimit} and
 * {@code lowerCircuitLimit} are not derivable from candle data and are
 * always left {@code null}. {@code week52High}/{@code week52Low} and
 * {@code averagePrice} are computed over whatever lookback window is
 * configured, not a true rolling 52-week figure unless that window is a
 * full year.
 */
public final class HistoricalFundamentalsClient implements FundamentalsClient {

    private final CandleDataClient candleDataClient;
    private final int lookbackDays;

    public HistoricalFundamentalsClient(CandleDataClient candleDataClient, int lookbackDays) {
        this.candleDataClient = candleDataClient;
        this.lookbackDays = lookbackDays;
    }

    @Override
    public StockFundamentals fetchFundamentals(String symbol, String exchange, String segment) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(lookbackDays);

        StockCandleSeries series = candleDataClient.fetchCandles(symbol, exchange, segment, startTime, endTime, 1440);
        List<Candle> candles = series.candles().stream()
                .sorted(Comparator.comparingLong(Candle::epochSeconds))
                .toList();

        if (candles.isEmpty()) {
            throw new GrowwApiException("No historical candle data returned for " + symbol);
        }

        Candle latest = candles.get(candles.size() - 1);
        Candle previous = candles.size() > 1 ? candles.get(candles.size() - 2) : null;

        Double previousClose = previous != null ? previous.close() : null;
        Double dayChange = previousClose != null ? latest.close() - previousClose : null;
        Double dayChangePerc = (dayChange != null && previousClose != 0)
                ? (dayChange / previousClose) * 100
                : null;

        double week52High = candles.stream().mapToDouble(Candle::high).max().orElse(latest.high());
        double week52Low = candles.stream().mapToDouble(Candle::low).min().orElse(latest.low());
        double averageClose = candles.stream().mapToDouble(Candle::close).average().orElse(latest.close());

        return new StockFundamentals(
                symbol,
                exchange,
                segment,
                latest.close(),
                latest.open(),
                latest.high(),
                latest.low(),
                previousClose,
                dayChange,
                dayChangePerc,
                latest.volume(),
                null, // market cap: only available from the live quote endpoint
                averageClose,
                week52High,
                week52Low,
                null, // upper circuit limit: only available from the live quote endpoint
                null); // lower circuit limit: only available from the live quote endpoint
    }
}
