package com.stockanalyzer.trade;

import com.stockanalyzer.model.PeriodType;
import com.stockanalyzer.model.PnlSummary;
import com.stockanalyzer.model.RealizedLot;
import com.stockanalyzer.model.Trade;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Rolls trades and realized lots up into one period's gain/loss statement.
 *
 * <p>Day, week, month and FY differ only in their boundaries, so they share
 * this code entirely. Everything produced here is derivable from the underlying
 * rows, which is why the stored copy can always be rebuilt.
 */
public final class PeriodAggregator {

    public PnlSummary aggregate(PeriodType periodType,
                                LocalDate periodStart,
                                LocalDate periodEnd,
                                String symbolOrNull,
                                List<Trade> trades,
                                List<RealizedLot> lots,
                                double unrealizedEnd) {
        List<Trade> scopedTrades = trades.stream()
                .filter(t -> symbolOrNull == null || t.symbol().equals(symbolOrNull))
                .filter(t -> !t.sessionDate().isBefore(periodStart) && !t.sessionDate().isAfter(periodEnd))
                .toList();
        List<RealizedLot> scopedLots = lots.stream()
                .filter(l -> symbolOrNull == null || l.symbol().equals(symbolOrNull))
                .toList();

        double gross = scopedLots.stream().mapToDouble(RealizedLot::grossPnl).sum();
        double charges = scopedLots.stream().mapToDouble(RealizedLot::chargesAllocated).sum();
        double net = scopedLots.stream().mapToDouble(RealizedLot::netPnl).sum();
        double turnover = scopedTrades.stream().mapToDouble(Trade::turnover).sum();

        List<RealizedLot> wins = scopedLots.stream().filter(RealizedLot::isWin).toList();
        List<RealizedLot> losses = scopedLots.stream().filter(l -> !l.isWin()).toList();

        double avgWin = wins.stream().mapToDouble(RealizedLot::netPnl).average().orElse(0);
        double avgLoss = losses.stream().mapToDouble(RealizedLot::netPnl).average().orElse(0);
        double grossWins = wins.stream().mapToDouble(RealizedLot::netPnl).sum();
        double grossLosses = Math.abs(losses.stream().mapToDouble(RealizedLot::netPnl).sum());
        double profitFactor = grossLosses == 0 ? (grossWins > 0 ? Double.POSITIVE_INFINITY : 0)
                : grossWins / grossLosses;

        return new PnlSummary(periodType, periodStart, periodEnd, symbolOrNull, null,
                scopedTrades.size(), scopedLots.size(), wins.size(), losses.size(),
                scopedLots.isEmpty() ? 0 : wins.size() * 100.0 / scopedLots.size(),
                round(gross), round(charges), round(net), round(turnover),
                turnover == 0 ? 0 : charges / turnover * 100.0,
                round(avgWin), round(avgLoss), profitFactor,
                scopedLots.stream().max(Comparator.comparingDouble(RealizedLot::netPnl))
                        .map(RealizedLot::netPnl).orElse(0.0),
                scopedLots.stream().min(Comparator.comparingDouble(RealizedLot::netPnl))
                        .map(RealizedLot::netPnl).orElse(0.0),
                round(unrealizedEnd));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
