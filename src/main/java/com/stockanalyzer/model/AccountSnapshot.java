package com.stockanalyzer.model;

import java.util.List;

/**
 * What the broker says the account is worth, at one instant.
 *
 * <p>This is a reading, not a derivation. The alternative - carrying a starting
 * balance forward and adding up realised P&amp;L - drifts the moment anything
 * happens outside the journal: a deposit, a withdrawal, a dividend, a fill this
 * process never imported, or charges the model priced differently from the
 * broker. None of those are edge cases in a real account, so the balance is
 * asked for rather than computed.
 *
 * @param cash           {@code clear_cash}: the credit balance, the whole ledger cash
 * @param marginUsed     {@code net_margin_used}: how much of that is blocked against positions
 * @param available      cash free to deploy right now ({@code cash - marginUsed})
 * @param collateral     {@code collateral_available}: pledged holdings usable as margin
 * @param holdings       demat holdings, marked to last traded price where one was available
 * @param fetchedAtEpoch when the broker was asked, in epoch seconds
 */
public record AccountSnapshot(double cash,
                              double marginUsed,
                              double available,
                              double collateral,
                              List<Holding> holdings,
                              long fetchedAtEpoch) {

    public AccountSnapshot {
        holdings = holdings == null ? List.of() : List.copyOf(holdings);
    }

    /** Market value of the holdings; any that could not be priced count at cost. */
    public double holdingsValue() {
        return holdings.stream().mapToDouble(Holding::value).sum();
    }

    /**
     * How many holdings are valued at cost because no quote came back. Non-zero
     * means {@link #totalValue()} is an approximation, and callers say so.
     */
    public long unpricedHoldings() {
        return holdings.stream().filter(h -> h.lastPrice() == null).count();
    }

    /** Cash plus what is held: the account's worth. */
    public double totalValue() {
        return cash + holdingsValue();
    }

    /**
     * One demat holding.
     *
     * @param lastPrice last traded price, null when no quote could be fetched
     */
    public record Holding(String symbol, String isin, double quantity, double averagePrice,
                          Double lastPrice) {

        /** Market value, or cost basis when there is no price to mark against. */
        public double value() {
            return quantity * (lastPrice == null ? averagePrice : lastPrice);
        }

        /** Unrealised gain at the last traded price; null when unpriced. */
        public Double unrealised() {
            return lastPrice == null ? null : quantity * (lastPrice - averagePrice);
        }
    }
}
