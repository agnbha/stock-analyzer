package com.stockanalyzer.trade;

import com.stockanalyzer.model.OpenPosition;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.RealizedLot;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches sells against the oldest open buys, one realized lot per pairing.
 *
 * <p>FIFO is what Indian equity tax treatment expects, and per-lot rows - rather
 * than a running average - are what make holding period, per-trade P&L and win
 * rate meaningful.
 *
 * <p>Intraday and delivery are matched separately: {@code product} is part of
 * the key, so an MIS buy never nets against a CNC position. Selling short is
 * supported symmetrically - an unmatched sell opens a short lot that a later
 * buy closes.
 *
 * <p>Charges are allocated pro-rata by quantity from both the opening and the
 * closing fill, so a partially consumed trade contributes only its share.
 */
public final class FifoLotMatcher {

    public Result match(List<Trade> trades) {
        List<Trade> ordered = trades.stream()
                .sorted(Comparator.comparingLong(Trade::executedTs).thenComparingLong(Trade::id))
                .toList();

        Map<Key, Deque<Slice>> openLongs = new LinkedHashMap<>();
        Map<Key, Deque<Slice>> openShorts = new LinkedHashMap<>();
        List<RealizedLot> lots = new ArrayList<>();

        for (Trade trade : ordered) {
            Key key = new Key(trade.symbol(), trade.product());
            int remaining = trade.quantity();

            if (trade.side() == Side.BUY) {
                Deque<Slice> shorts = openShorts.computeIfAbsent(key, k -> new ArrayDeque<>());
                remaining = close(lots, shorts, trade, remaining, false);
                if (remaining > 0) {
                    openLongs.computeIfAbsent(key, k -> new ArrayDeque<>())
                            .addLast(new Slice(trade, remaining));
                }
            } else {
                Deque<Slice> longs = openLongs.computeIfAbsent(key, k -> new ArrayDeque<>());
                remaining = close(lots, longs, trade, remaining, true);
                if (remaining > 0) {
                    openShorts.computeIfAbsent(key, k -> new ArrayDeque<>())
                            .addLast(new Slice(trade, remaining));
                }
            }
        }

        List<OpenPosition> open = new ArrayList<>();
        openLongs.forEach((key, slices) -> position(open, key, slices, 1));
        openShorts.forEach((key, slices) -> position(open, key, slices, -1));
        return new Result(List.copyOf(lots), List.copyOf(open));
    }

    /**
     * Consumes open slices with {@code closing}. When {@code closingIsSell} the
     * open slices are longs (bought earlier); otherwise they are shorts.
     * Returns the quantity of {@code closing} left unmatched.
     */
    private int close(List<RealizedLot> lots, Deque<Slice> openSlices, Trade closing, int remaining,
                      boolean closingIsSell) {
        while (remaining > 0 && !openSlices.isEmpty()) {
            Slice oldest = openSlices.peekFirst();
            int quantity = Math.min(remaining, oldest.remaining);

            Trade buy = closingIsSell ? oldest.trade : closing;
            Trade sell = closingIsSell ? closing : oldest.trade;
            double grossPnl = (sell.price() - buy.price()) * quantity;
            double charges = allocate(buy, quantity) + allocate(sell, quantity);
            double netPnl = grossPnl - charges;
            double entryPrice = closingIsSell ? buy.price() : sell.price();
            long openedTs = oldest.trade.executedTs();
            long closedTs = closing.executedTs();

            lots.add(new RealizedLot(0, closing.symbol(), buy.id(), sell.id(), closing.product(), quantity,
                    buy.price(), sell.price(), openedTs, closedTs,
                    (int) Math.max((closedTs - openedTs) / 60, 0),
                    round(grossPnl), round(charges), round(netPnl),
                    entryPrice * quantity == 0 ? 0 : netPnl / (entryPrice * quantity) * 100.0));

            oldest.remaining -= quantity;
            remaining -= quantity;
            if (oldest.remaining == 0) {
                openSlices.pollFirst();
            }
        }
        return remaining;
    }

    private static void position(List<OpenPosition> into, Key key, Deque<Slice> slices, int sign) {
        int quantity = slices.stream().mapToInt(slice -> slice.remaining).sum();
        if (quantity == 0) {
            return;
        }
        double cost = slices.stream().mapToDouble(slice -> slice.trade.price() * slice.remaining).sum();
        long openedTs = slices.stream().mapToLong(slice -> slice.trade.executedTs()).min().orElse(0);
        into.add(new OpenPosition(key.symbol(), key.product(), sign * quantity, cost / quantity, openedTs));
    }

    private static double allocate(Trade trade, int quantity) {
        return trade.quantity() == 0 ? 0 : trade.chargesTotal() * quantity / trade.quantity();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Key(String symbol, Product product) {
    }

    private static final class Slice {
        private final Trade trade;
        private int remaining;

        private Slice(Trade trade, int remaining) {
            this.trade = trade;
            this.remaining = remaining;
        }
    }

    public record Result(List<RealizedLot> lots, List<OpenPosition> openPositions) {
    }
}
