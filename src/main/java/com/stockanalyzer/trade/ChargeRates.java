package com.stockanalyzer.trade;

/**
 * The charge schedule, as configured. Verify these against a real contract note
 * once; after that {@code charges.prefer.broker.actuals} keeps them out of the
 * way for anything the broker itself reports.
 *
 * <p>All percentages are percent, not fractions: 0.05 means 0.05%.
 */
public record ChargeRates(double brokerageIntradayPct,
                          double brokerageIntradayMax,
                          double brokerageDeliveryPct,
                          double sttIntradaySellPct,
                          double sttDeliveryPct,
                          double exchangeTxnPct,
                          double sebiPct,
                          double stampDutyBuyPct,
                          double gstPct) {

    public static ChargeRates defaults() {
        return new ChargeRates(0.05, 20.0, 0.0, 0.025, 0.1, 0.00297, 0.0001, 0.003, 18.0);
    }
}
