package com.stockanalyzer.trade;

import com.stockanalyzer.model.OpenPosition;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.RealizedLot;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FifoLotMatcherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 27);
    private final FifoLotMatcher matcher = new FifoLotMatcher();

    private static Trade trade(long id, String symbol, Side side, int quantity, double price,
                               int minute, Product product, double charges) {
        return new Trade(id, symbol, "t" + id, null, DAY, 1756000000L + minute * 60L, side, quantity, price,
                product, charges, null, Trade.ChargesSource.MODELLED, Trade.TradeSource.MANUAL, null);
    }

    @Test
    @DisplayName("a sell consumes the oldest buy first")
    void matchesOldestFirst() {
        List<Trade> trades = List.of(
                trade(1, "RELIANCE", Side.BUY, 10, 100, 0, Product.MIS, 0),
                trade(2, "RELIANCE", Side.BUY, 10, 110, 1, Product.MIS, 0),
                trade(3, "RELIANCE", Side.SELL, 10, 120, 2, Product.MIS, 0));

        FifoLotMatcher.Result result = matcher.match(trades);

        assertEquals(1, result.lots().size());
        RealizedLot lot = result.lots().getFirst();
        assertEquals(100.0, lot.buyPrice(), 0.0001, "the 100 lot was bought first");
        assertEquals(200.0, lot.grossPnl(), 0.0001);
        assertEquals(1, result.openPositions().size());
        assertEquals(110.0, result.openPositions().getFirst().avgCost(), 0.0001);
    }

    @Test
    @DisplayName("one sell can close several buy lots, one row each")
    void splitsAcrossLots() {
        List<Trade> trades = List.of(
                trade(1, "TCS", Side.BUY, 5, 100, 0, Product.MIS, 0),
                trade(2, "TCS", Side.BUY, 5, 200, 1, Product.MIS, 0),
                trade(3, "TCS", Side.SELL, 10, 300, 2, Product.MIS, 0));

        FifoLotMatcher.Result result = matcher.match(trades);

        assertEquals(2, result.lots().size());
        assertEquals(1000.0, result.lots().get(0).grossPnl(), 0.0001);
        assertEquals(500.0, result.lots().get(1).grossPnl(), 0.0001);
        assertTrue(result.openPositions().isEmpty());
    }

    @Test
    @DisplayName("a partial sell leaves the rest of the lot open")
    void partialFillLeavesRemainder() {
        List<Trade> trades = List.of(
                trade(1, "INFY", Side.BUY, 10, 100, 0, Product.MIS, 0),
                trade(2, "INFY", Side.SELL, 4, 105, 1, Product.MIS, 0));

        FifoLotMatcher.Result result = matcher.match(trades);

        assertEquals(4, result.lots().getFirst().quantity());
        assertEquals(6, result.openPositions().getFirst().quantity());
    }

    @Test
    @DisplayName("selling short then covering realises the gain the same way")
    void shortThenCover() {
        List<Trade> trades = List.of(
                trade(1, "HDFCBANK", Side.SELL, 10, 200, 0, Product.MIS, 0),
                trade(2, "HDFCBANK", Side.BUY, 10, 180, 5, Product.MIS, 0));

        FifoLotMatcher.Result result = matcher.match(trades);

        RealizedLot lot = result.lots().getFirst();
        assertEquals(200.0, lot.grossPnl(), 0.0001, "sold high, bought back low");
        assertEquals(5, lot.holdingMinutes());
        assertTrue(result.openPositions().isEmpty());
    }

    @Test
    @DisplayName("intraday and delivery never net against each other")
    void productsAreKeptApart() {
        List<Trade> trades = List.of(
                trade(1, "SBIN", Side.BUY, 10, 100, 0, Product.CNC, 0),
                trade(2, "SBIN", Side.SELL, 10, 120, 1, Product.MIS, 0));

        FifoLotMatcher.Result result = matcher.match(trades);

        assertTrue(result.lots().isEmpty(), "the MIS sell must not close the CNC position");
        assertEquals(2, result.openPositions().size(), "one long CNC, one short MIS");
    }

    @Test
    @DisplayName("charges are allocated pro-rata from both fills")
    void allocatesChargesProRata() {
        List<Trade> trades = List.of(
                trade(1, "WIPRO", Side.BUY, 10, 100, 0, Product.MIS, 20),
                trade(2, "WIPRO", Side.SELL, 5, 110, 1, Product.MIS, 10));

        RealizedLot lot = matcher.match(trades).lots().getFirst();

        // Half the buy's charges (10) plus all of the sell's (10).
        assertEquals(20.0, lot.chargesAllocated(), 0.0001);
        assertEquals(50.0, lot.grossPnl(), 0.0001);
        assertEquals(30.0, lot.netPnl(), 0.0001);
    }
}
