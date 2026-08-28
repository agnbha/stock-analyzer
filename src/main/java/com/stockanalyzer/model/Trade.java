package com.stockanalyzer.model;

import java.time.LocalDate;

/**
 * One executed fill. {@code brokerTradeId} is the natural key: real when the
 * broker supplies one, deterministically synthesised otherwise, so re-importing
 * an overlapping date range is a no-op.
 */
public record Trade(long id,
                    String symbol,
                    String brokerTradeId,
                    String orderId,
                    LocalDate sessionDate,
                    long executedTs,
                    Side side,
                    int quantity,
                    double price,
                    Product product,
                    double chargesTotal,
                    String chargesJson,
                    ChargesSource chargesSource,
                    TradeSource source,
                    String notes) {

    public enum ChargesSource { BROKER, MODELLED }

    public enum TradeSource { BROKER, CSV, MANUAL }

    public double turnover() {
        return quantity * price;
    }

    public Trade withId(long newId) {
        return new Trade(newId, symbol, brokerTradeId, orderId, sessionDate, executedTs, side, quantity,
                price, product, chargesTotal, chargesJson, chargesSource, source, notes);
    }

    public Trade withCharges(double total, String json, ChargesSource src) {
        return new Trade(id, symbol, brokerTradeId, orderId, sessionDate, executedTs, side, quantity,
                price, product, total, json, src, source, notes);
    }
}
