package com.stockanalyzer.live;

import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.TradeRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Each broker account's fills for a session, the first account first. The monitor follows a symbol closely when any
 * account has traded it, and works out sell targets per account, because two accounts' buys are two positions with
 * two break-evens.
 */
@FunctionalInterface
public interface AccountFills {

    List<Account> on(LocalDate sessionDate);

    /**
     * @param name    the account's name
     * @param primary whether it is the first account, whose alerts do not name it
     * @param fills   its fills of the session, any symbol
     */
    record Account(String name, boolean primary, List<Trade> fills) {
    }

    /** A single account's journal: one account, the first. */
    static AccountFills of(TradeRepository trades) {
        return date -> List.of(new Account(null, true, trades.findRange(date, date)));
    }
}
