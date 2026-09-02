package com.stockanalyzer.client;

import com.stockanalyzer.model.AccountSnapshot;

/**
 * Reads the account's balance from the broker.
 *
 * <p>Separate from {@code ExecutionDataClient} because the two answer different
 * questions - that one reports what was traded, this one reports what is held -
 * and because a balance is worth reading on days with no trades at all.
 */
public interface AccountDataClient {

    AccountSnapshot fetch();
}
