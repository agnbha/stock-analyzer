package com.stockanalyzer.model;

import java.time.LocalDate;
import java.util.List;

/**
 * The analysis result for one instrument on one session: the day's OHLCV plus
 * the ranked gain opportunities found in it. This is the row that accumulates
 * month over month.
 */
public record DailyGainSummary(String symbol,
                               LocalDate sessionDate,
                               int intervalMinutes,
                               double open,
                               double high,
                               double low,
                               double close,
                               long volume,
                               Double dayChangePct,
                               int candleCount,
                               long firstCandleTs,
                               long lastCandleTs,
                               List<GainOpportunity> opportunities) {

    public double dayRangePct() {
        return low > 0 ? (high - low) / low * 100.0 : 0.0;
    }
}
