package com.stockanalyzer.trade;

import com.stockanalyzer.model.Charges;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowwChargeModelTest {

    private final GrowwChargeModel model = new GrowwChargeModel(ChargeRates.defaults());

    private static Trade trade(Side side, Product product, int quantity, double price) {
        return new Trade(1, "RELIANCE", "t1", null, LocalDate.of(2026, 8, 27), 1756000000L, side,
                quantity, price, product, 0, null, Trade.ChargesSource.MODELLED,
                Trade.TradeSource.MANUAL, null);
    }

    @Test
    @DisplayName("intraday brokerage is capped")
    void brokerageIsCapped() {
        Charges small = model.compute(trade(Side.BUY, Product.MIS, 10, 1000));
        Charges large = model.compute(trade(Side.BUY, Product.MIS, 1000, 1000));

        assertEquals(5.0, small.brokerage(), 0.01, "0.05% of 10,000");
        assertEquals(20.0, large.brokerage(), 0.01, "capped at 20 per side");
    }

    @Test
    @DisplayName("intraday STT applies to the sell side only")
    void intradaySttIsSellSideOnly() {
        assertEquals(0.0, model.compute(trade(Side.BUY, Product.MIS, 100, 1000)).stt(), 0.001);
        assertEquals(25.0, model.compute(trade(Side.SELL, Product.MIS, 100, 1000)).stt(), 0.01);
    }

    @Test
    @DisplayName("delivery STT applies to both sides")
    void deliverySttAppliesBothWays() {
        assertEquals(100.0, model.compute(trade(Side.BUY, Product.CNC, 100, 1000)).stt(), 0.01);
        assertEquals(100.0, model.compute(trade(Side.SELL, Product.CNC, 100, 1000)).stt(), 0.01);
    }

    @Test
    @DisplayName("stamp duty is charged on purchases only, and GST rides on the fees")
    void stampDutyAndGst() {
        Charges buy = model.compute(trade(Side.BUY, Product.MIS, 100, 1000));
        Charges sell = model.compute(trade(Side.SELL, Product.MIS, 100, 1000));

        assertEquals(3.0, buy.stampDuty(), 0.01);
        assertEquals(0.0, sell.stampDuty(), 0.001);
        assertEquals((buy.brokerage() + buy.exchangeTxn() + buy.sebi()) * 0.18, buy.gst(), 0.01);
    }

    @Test
    @DisplayName("charges are a real fraction of a small intraday move")
    void chargesMatterOnSmallMoves() {
        double turnover = 100_000;
        Charges buy = model.compute(trade(Side.BUY, Product.MIS, 100, 1000));
        Charges sell = model.compute(trade(Side.SELL, Product.MIS, 100, 1000));

        double roundTripPct = (buy.total() + sell.total()) / turnover * 100;
        assertTrue(roundTripPct > 0.03 && roundTripPct < 0.15,
                "round trip cost should land in the tens of basis points, was " + roundTripPct);
    }
}
