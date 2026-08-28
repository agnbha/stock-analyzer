package com.stockanalyzer.model;

import java.time.LocalDate;

/**
 * Gain/loss over one period. Every figure here is derivable from the trade and
 * realized-lot rows; the stored copy is a cache that {@code pnl --rebuild}
 * regenerates.
 */
public record PnlSummary(PeriodType periodType,
                         LocalDate periodStart,
                         LocalDate periodEnd,
                         String symbol,
                         Product product,
                         int trades,
                         int closedLots,
                         int wins,
                         int losses,
                         double winRate,
                         double grossPnl,
                         double charges,
                         double netPnl,
                         double turnover,
                         double chargesPctTurnover,
                         double avgWin,
                         double avgLoss,
                         double profitFactor,
                         double bestLotPnl,
                         double worstLotPnl,
                         double unrealizedEnd) {
}
