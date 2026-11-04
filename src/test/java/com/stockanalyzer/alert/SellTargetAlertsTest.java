package com.stockanalyzer.alert;

import com.stockanalyzer.model.Alert;
import com.stockanalyzer.model.AlertSeverity;
import com.stockanalyzer.model.Candle;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.util.MarketClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SellTargetAlertsTest {

    private static final MarketClock CLOCK = MarketClock.nse();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);
    private static final String SYMBOL = "BHARTIARTL";

    private final SellTargetAlerts alerts = new SellTargetAlerts(List.of(0.6, 1.0), 0.05, 1, 10);

    private static long at(int hour, int minute) {
        return CLOCK.epochOf(DAY, LocalTime.of(hour, minute));
    }

    private static long at(int hour, int minute, int second) {
        return at(hour, minute) + second;
    }

    private static Trade fill(String symbol, Side side, int quantity, double price, long executedAt) {
        return new Trade(0, symbol, "t" + executedAt + side + quantity, "o1", DAY, executedAt, side, quantity, price,
                Product.MIS, 0, null, Trade.ChargesSource.MODELLED, Trade.TradeSource.BROKER, null);
    }

    private static Candle candle(int hour, int minute, double high) {
        return new Candle(at(hour, minute), high - 1, high, high - 2, high - 0.5, 1000);
    }

    private List<Alert> due(List<Trade> fills, List<Candle> candles, long now) {
        return alerts.due(DAY, SYMBOL, fills, candles, now);
    }

    @Test
    @DisplayName("nothing happens until the price reaches the first target")
    void silentBelowTheTarget() {
        List<Trade> fills = List.of(fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 10)));

        // 0.6% over 2000 is 2012.00; the high only gets to 2011.95.
        assertTrue(due(fills, List.of(candle(9, 31, 2011.95)), at(9, 32)).isEmpty());
    }

    @Test
    @DisplayName("at +0.6% there is one alert, saying to sell and which limit to set")
    void firstTarget() {
        List<Trade> fills = List.of(fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 10)));

        List<Alert> due = due(fills, List.of(candle(9, 31, 2012.00)), at(9, 32));

        assertEquals(1, due.size());
        Alert alert = due.getFirst();
        assertEquals("sell.target.0.6", alert.rule());
        assertEquals(AlertSeverity.URGENT, alert.severity());
        assertEquals(SYMBOL, alert.symbol());
        assertTrue(alert.message().contains("2000.00"), alert.message());
        assertTrue(alert.message().contains("100 shares"), alert.message());
        assertTrue(alert.message().contains("limit at 2012.00"), alert.message());
    }

    @Test
    @DisplayName("at +1% there is a second alert with its own limit; both once the price is past both")
    void bothTargets() {
        List<Trade> fills = List.of(fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 10)));

        List<Alert> due = due(fills, List.of(candle(9, 31, 2012.50), candle(9, 32, 2020.00)), at(9, 33));

        assertEquals(List.of("sell.target.0.6", "sell.target.1"), due.stream().map(Alert::rule).toList());
        assertTrue(due.get(0).message().contains("limit at 2012.00"), due.get(0).message());
        assertTrue(due.get(1).message().contains("limit at 2020.00"), due.get(1).message());
    }

    @Test
    @DisplayName("the limit is the target rounded up to the next tick, so the order is never below the target")
    void limitRoundsUp() {
        // 2065.00 + 0.6% = 2077.39 -> 2077.40; + 1% = 2085.65, already on a tick.
        List<Trade> fills = List.of(fill(SYMBOL, Side.BUY, 100, 2065.00, at(9, 30, 10)));

        List<Alert> due = due(fills, List.of(candle(9, 31, 2090.00)), at(9, 32));

        assertTrue(due.get(0).message().contains("limit at 2077.40"), due.get(0).message());
        assertTrue(due.get(1).message().contains("limit at 2085.65"), due.get(1).message());
    }

    @Test
    @DisplayName("a price one tick short of the rounded limit has not reached it")
    void theRoundedLimitIsWhatMustBeReached() {
        List<Trade> fills = List.of(fill(SYMBOL, Side.BUY, 100, 2065.00, at(9, 30, 10)));

        // The exact target is 2077.39, but the limit to set is 2077.40: at 2077.39 that order would not fill.
        assertTrue(due(fills, List.of(candle(9, 31, 2077.39)), at(9, 32)).isEmpty());
        assertEquals(1, due(fills, List.of(candle(9, 31, 2077.40)), at(9, 32)).size());
    }

    @Test
    @DisplayName("several buys at different prices are averaged by quantity")
    void averagePrice() {
        // 100 @ 2000 and 300 @ 2010: (200,000 + 603,000) / 400 = 2007.50; +0.6% = 2019.545 -> 2019.55.
        List<Trade> fills = List.of(
                fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 10)),
                fill(SYMBOL, Side.BUY, 300, 2010.00, at(9, 40, 10)));

        // 2019.50 is above the first buy's targets but short of the average's.
        assertTrue(due(fills, List.of(candle(9, 41, 2019.50)), at(9, 42)).isEmpty());

        List<Alert> due = due(fills, List.of(candle(9, 41, 2019.55)), at(9, 42));

        assertEquals(1, due.size());
        assertTrue(due.getFirst().message().contains("average buy of 2007.50"), due.getFirst().message());
        assertTrue(due.getFirst().message().contains("400 shares"), due.getFirst().message());
        assertTrue(due.getFirst().message().contains("limit at 2019.55"), due.getFirst().message());
    }

    @Test
    @DisplayName("only prices after the last purchase count: a high in the minute of the buy proves nothing")
    void pricesBeforeThePurchaseAreIgnored() {
        List<Trade> fills = List.of(fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 40)));

        // The 09:30 candle is the minute the buy happened in, and 09:20 was long before it.
        List<Candle> candles = List.of(candle(9, 20, 2050.00), candle(9, 30, 2050.00), candle(9, 31, 2005.00));

        assertTrue(due(fills, candles, at(9, 32)).isEmpty());
    }

    @Test
    @DisplayName("a later purchase moves the targets, so the earlier high no longer counts")
    void aLaterBuyResetsTheComparison() {
        List<Trade> fills = List.of(
                fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 10)),
                fill(SYMBOL, Side.BUY, 100, 2010.00, at(9, 40, 10)));
        // 2013 was above the first buy's +0.6% (2012.00) but came before the second buy.
        List<Candle> candles = List.of(candle(9, 35, 2013.00), candle(9, 41, 2010.00));

        assertTrue(due(fills, candles, at(9, 42)).isEmpty());
    }

    @Test
    @DisplayName("a position sold out has nothing left to sell; a part-sold one still does, for what is left")
    void soldPositions() {
        Trade buy = fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 10));
        List<Candle> candles = List.of(candle(9, 31, 2013.00));

        assertTrue(due(List.of(buy, fill(SYMBOL, Side.SELL, 100, 2010.00, at(9, 31, 30))), candles, at(9, 32)).isEmpty());

        List<Alert> due = due(List.of(buy, fill(SYMBOL, Side.SELL, 40, 2010.00, at(9, 31, 30))), candles, at(9, 32));
        assertEquals(1, due.size());
        assertTrue(due.getFirst().message().contains("60 shares"), due.getFirst().message());
    }

    @Test
    @DisplayName("other symbols' fills are not this symbol's purchase")
    void otherSymbolsAreIgnored() {
        List<Trade> fills = List.of(fill("TCS", Side.BUY, 100, 2000.00, at(9, 30, 10)));

        assertTrue(due(fills, List.of(candle(9, 31, 2100.00)), at(9, 32)).isEmpty());
        assertTrue(due(List.of(), List.of(candle(9, 31, 2100.00)), at(9, 32)).isEmpty(), "no purchase, no alert");
    }

    @Test
    @DisplayName("a target touched too long ago is not announced, so a restart does not replay the morning")
    void staleTargetsAreSkipped() {
        List<Trade> fills = List.of(fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 10)));
        List<Candle> candles = List.of(candle(9, 31, 2015.00), candle(9, 32, 2005.00));

        // The target was touched in the 09:31 candle, which closed at 09:32. Ten minutes later it is still news...
        assertEquals(1, due(fills, candles, at(9, 42)).size());
        // ...and eleven minutes later it is not.
        assertTrue(due(fills, candles, at(9, 43)).isEmpty());
    }

    @Test
    @DisplayName("a target the price is still at after a restart is announced, however long ago it was first reached")
    void stillThereIsNotStale() {
        List<Trade> fills = List.of(fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 10)));
        List<Candle> candles = List.of(candle(9, 31, 2015.00), candle(10, 29, 2014.00));

        assertEquals(1, due(fills, candles, at(10, 30)).size());
    }

    @Test
    @DisplayName("the idempotency key names the symbol, the average and the target, so one is sent once per average")
    void idempotencyKey() {
        List<Trade> one = List.of(fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 10)));
        List<Trade> two = List.of(fill(SYMBOL, Side.BUY, 100, 2000.00, at(9, 30, 10)),
                fill(SYMBOL, Side.BUY, 100, 2010.00, at(9, 40, 10)));
        List<Candle> candles = List.of(candle(9, 41, 2030.00));

        String first = due(one, List.of(candle(9, 31, 2030.00)), at(9, 32)).getFirst().idempotencyKey();
        String again = due(one, List.of(candle(9, 31, 2030.00), candle(9, 32, 2031.00)), at(9, 33)).getFirst()
                .idempotencyKey();
        String afterSecondBuy = due(two, candles, at(9, 42)).getFirst().idempotencyKey();

        assertEquals(first, again, "the same position on a later poll is the same alert");
        assertNotEquals(first, afterSecondBuy, "a new average is a new alert");
        assertTrue(first.contains(SYMBOL) && first.contains("2000.00"), first);
    }
}
