package com.stockanalyzer.trade;

import com.stockanalyzer.model.Charges;
import com.stockanalyzer.model.Product;
import com.stockanalyzer.model.Side;
import com.stockanalyzer.model.Trade;

/** The configured schedule, applied per fill. */
public final class GrowwChargeModel implements ChargeModel {

    private final ChargeRates rates;

    public GrowwChargeModel(ChargeRates rates) {
        this.rates = rates;
    }

    @Override
    public Charges compute(Trade trade) {
        double turnover = trade.turnover();
        boolean intraday = trade.product() == Product.MIS;
        boolean selling = trade.side() == Side.SELL;

        double brokerage = intraday
                ? Math.min(pct(turnover, rates.brokerageIntradayPct()), rates.brokerageIntradayMax())
                : pct(turnover, rates.brokerageDeliveryPct());

        double stt;
        if (intraday) {
            stt = selling ? pct(turnover, rates.sttIntradaySellPct()) : 0.0;
        } else {
            stt = pct(turnover, rates.sttDeliveryPct());
        }

        double exchangeTxn = pct(turnover, rates.exchangeTxnPct());
        double sebi = pct(turnover, rates.sebiPct());
        double stampDuty = selling ? 0.0 : pct(turnover, rates.stampDutyBuyPct());
        double gst = pct(brokerage + exchangeTxn + sebi, rates.gstPct());

        return new Charges(round(brokerage), round(stt), round(exchangeTxn), round(sebi),
                round(stampDuty), round(gst));
    }

    private static double pct(double base, double percent) {
        return base * percent / 100.0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
