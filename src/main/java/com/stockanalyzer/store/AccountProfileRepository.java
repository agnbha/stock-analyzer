package com.stockanalyzer.store;

import java.util.List;
import java.util.Map;

/**
 * The broker accounts ("profiles") the application knows. Everything that belongs to an account - fills, lots, open
 * positions, period statements, balances and holdings - carries the name of its profile; stock data carries none.
 */
public interface AccountProfileRepository {

    /** The profile a repository works on when it is not given one, and the first profile's name when none is set. */
    String DEFAULT_PROFILE = com.stockanalyzer.config.GrowwProfile.DEFAULT_NAME;

    /** The tables whose rows belong to a profile. */
    List<String> TABLES = List.of("trade", "realized_lot", "open_position", "pnl_period", "account_balance",
            "account_holding");

    /**
     * Records the profiles the application was started with, in order. A profile no longer configured keeps its row,
     * and its data, so its history can still be looked at; it moves to the end of the list.
     */
    void register(List<String> namesInOrder);

    /**
     * Gives every row that has no profile yet (the journal from before profiles, or a row written by an older build)
     * to {@code profile}. Returns how many rows moved, per table, leaving out tables with none.
     */
    Map<String, Integer> adoptUnassigned(String profile);

    /**
     * Moves every row of profile {@code from} to profile {@code to}: a rename. Refused when {@code to} already has
     * rows of its own, because two accounts' journals would then be merged. Returns how many rows moved, per table.
     */
    Map<String, Integer> rename(String from, String to);

    /** Every profile registered or holding data, registered ones first in their order. */
    List<Summary> summaries();

    /**
     * @param position        where it stands in the configuration, or null when it is no longer configured
     * @param lastTradeDate   the session of its latest fill, or null
     * @param lastBalanceDate the latest day its balance was recorded, or null
     */
    record Summary(String name, Integer position, int trades, int openPositions, String lastTradeDate,
                   String lastBalanceDate) {
    }
}
