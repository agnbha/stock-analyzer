package com.stockanalyzer.trade;

import com.stockanalyzer.model.Charges;
import com.stockanalyzer.model.Trade;

/**
 * What a trade costs beyond its price.
 *
 * <p>On Indian equity intraday, brokerage plus STT, exchange transaction
 * charges, SEBI fees, stamp duty and GST are a large fraction of a small move -
 * a gross-P&L report would be actively misleading, so this is never optional.
 */
public interface ChargeModel {

    Charges compute(Trade trade);
}
