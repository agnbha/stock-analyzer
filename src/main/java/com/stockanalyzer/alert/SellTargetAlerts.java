package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.AlertSeverity;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Says when a purchase is worth selling.
 *
 * <p>Everything bought in a symbol today has one price: the average of the buys, weighted by
 * quantity, because a position built in several fills at different prices has a single break-even.
 * Each target is a percentage above that average (0.6% and 1% by default). The first time the
 * price reaches a target there is one alert, which says to sell and gives the limit price to set:
 * the target rounded up to the next tick, so the order is at or above the target and can fill the
 * moment the price is there.
 *
 * <p>A later purchase changes the average and with it the targets, so each average gets its own
 * alert per target, and an alert that has gone out for one average is not repeated for it. A
 * position that has been sold out has nothing left to say, and so has one whose target was last
 * touched too long ago to act on, which is what keeps a restart from announcing the morning.
 *
 * <p>Pure: it is given the day's fills and candles and returns the alerts that are due. Never
 * sending the same alert twice is the {@link AlertPolicy}'s job, through the idempotency key.
 */
public final class SellTargetAlerts {

    static final String RULE_PREFIX = "sell.target.";

    private final List<BigDecimal> targetsPct;
    private final BigDecimal tickSize;
    private final int intervalMinutes;
    private final int maxAgeMinutes;

    /**
     * @param targetsPct      percentages above the average buy price, each a separate alert
     * @param tickSize        the smallest price step, in rupees; limits are rounded up to it
     * @param intervalMinutes the candle length the prices come in
     * @param maxAgeMinutes   how long after a target was last touched an alert is still worth sending
     */
    public SellTargetAlerts(List<Double> targetsPct, double tickSize, int intervalMinutes, int maxAgeMinutes) {
        this.targetsPct = targetsPct.stream().sorted().map(BigDecimal::valueOf).toList();
        this.tickSize = BigDecimal.valueOf(tickSize);
        this.intervalMinutes = intervalMinutes;
        this.maxAgeMinutes = maxAgeMinutes;
    }

    /**
     * @param fills   every fill of the session, any symbol; the ones for {@code symbol} are used
     * @param candles that symbol's candles so far, the forming one included, oldest first
     * @param now     epoch seconds
     */
    public List<Alert> due(LocalDate sessionDate, String symbol, List<Trade> fills, List<Candle> candles, long now) {
        long bought = 0;
        long sold = 0;
        BigDecimal cost = BigDecimal.ZERO;
        long lastBuyAt = 0;
        for (Trade fill : fills) {
            if (!fill.symbol().equals(symbol)) {
                continue;
            }
            if (fill.side() == Side.BUY) {
                bought += fill.quantity();
                cost = cost.add(BigDecimal.valueOf(fill.price()).multiply(BigDecimal.valueOf(fill.quantity())));
                lastBuyAt = Math.max(lastBuyAt, fill.executedTs());
            } else {
                sold += fill.quantity();
            }
        }
        long held = bought - sold;
        if (bought == 0 || held <= 0) {
            return List.of();
        }
        BigDecimal average = cost.divide(BigDecimal.valueOf(bought), 6, RoundingMode.HALF_UP);

        // Only prices after the last purchase count: before it the position was not this one yet.
        long intervalSeconds = intervalMinutes * 60L;
        long firstCandle = (lastBuyAt / intervalSeconds + 1) * intervalSeconds;
        List<Candle> after = candles.stream().filter(c -> c.epochSeconds() >= firstCandle).toList();
        if (after.isEmpty()) {
            return List.of();
        }

        List<Alert> alerts = new ArrayList<>();
        for (BigDecimal pct : targetsPct) {
            BigDecimal limit = roundUpToTick(average.multiply(
                    BigDecimal.ONE.add(pct.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP))));
            Candle touched = latestAtOrAbove(after, limit);
            if (touched == null || now - (touched.epochSeconds() + intervalSeconds) > maxAgeMinutes * 60L) {
                continue;
            }
            String label = plain(pct);
            String message = String.format(Locale.ROOT,
                    "%s is up %s%% on your average buy of %s (%d shares). Sell now: set the limit at %s.",
                    symbol, label, money(average), held, money(limit));
            alerts.add(new Alert(sessionDate, now, symbol, RULE_PREFIX + label, AlertSeverity.URGENT,
                    "Sell target +" + label + "%", message,
                    "sellTarget|" + sessionDate + "|" + symbol + "|" + money(average) + "|" + label));
        }
        return alerts;
    }

    private BigDecimal roundUpToTick(BigDecimal price) {
        return price.divide(tickSize, 0, RoundingMode.CEILING).multiply(tickSize);
    }

    private static Candle latestAtOrAbove(List<Candle> candles, BigDecimal limit) {
        for (int i = candles.size() - 1; i >= 0; i--) {
            if (BigDecimal.valueOf(candles.get(i).high()).compareTo(limit) >= 0) {
                return candles.get(i);
            }
        }
        return null;
    }

    private static String plain(BigDecimal pct) {
        return pct.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
