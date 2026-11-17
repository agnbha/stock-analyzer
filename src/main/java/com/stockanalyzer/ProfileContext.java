package com.stockanalyzer;

import com.stockanalyzer.auth.GrowwAuthenticator;
import com.stockanalyzer.client.AccountDataClient;
import com.stockanalyzer.config.GrowwProfile;
import com.stockanalyzer.store.AccountBalanceRepository;
import com.stockanalyzer.store.RealizedLotRepository;
import com.stockanalyzer.store.TradeNoteRepository;
import com.stockanalyzer.store.TradeReasonRepository;
import com.stockanalyzer.store.TradeRepository;
import com.stockanalyzer.trade.BrokerFillSync;
import com.stockanalyzer.trade.ExecutionDataClient;
import com.stockanalyzer.trade.ReasonAttributor;
import com.stockanalyzer.trade.TradeJournalService;
import com.stockanalyzer.trade.TradeNoteService;

import java.util.function.Supplier;

/**
 * Everything that belongs to one Groww account: its login and the clients that use it, and its journal - fills, lots,
 * positions, statements, balance and holdings - with the services over them. Each repository here sees only this
 * account's rows, so matching, P&L and notes can never mix two accounts. Built by {@link AppContext}, which keeps the
 * shared stock data.
 */
public final class ProfileContext {

    private final GrowwProfile profile;
    private final Supplier<GrowwAuthenticator> authenticator;
    private final Supplier<ExecutionDataClient> executionClient;
    private final Supplier<AccountDataClient> accountDataClient;
    private final TradeRepository tradeRepository;
    private final RealizedLotRepository realizedLotRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final TradeReasonRepository tradeReasonRepository;
    private final TradeNoteRepository tradeNoteRepository;
    private final TradeJournalService tradeJournalService;
    private final ReasonAttributor reasonAttributor;
    private final TradeNoteService tradeNoteService;

    ProfileContext(GrowwProfile profile,
                   Supplier<GrowwAuthenticator> authenticator,
                   Supplier<ExecutionDataClient> executionClient,
                   Supplier<AccountDataClient> accountDataClient,
                   TradeRepository tradeRepository,
                   RealizedLotRepository realizedLotRepository,
                   AccountBalanceRepository accountBalanceRepository,
                   TradeReasonRepository tradeReasonRepository,
                   TradeNoteRepository tradeNoteRepository,
                   TradeJournalService tradeJournalService,
                   ReasonAttributor reasonAttributor,
                   TradeNoteService tradeNoteService) {
        this.profile = profile;
        this.authenticator = authenticator;
        this.executionClient = executionClient;
        this.accountDataClient = accountDataClient;
        this.tradeRepository = tradeRepository;
        this.realizedLotRepository = realizedLotRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.tradeReasonRepository = tradeReasonRepository;
        this.tradeNoteRepository = tradeNoteRepository;
        this.tradeJournalService = tradeJournalService;
        this.reasonAttributor = reasonAttributor;
        this.tradeNoteService = tradeNoteService;
    }

    public String name() {
        return profile.name();
    }

    /** The first profile: its login also fetches the stock data, and commands act on it when told no other. */
    public boolean primary() {
        return profile.primary();
    }

    public GrowwProfile profile() {
        return profile;
    }

    /** Built on first use: a command that only reads the journal never logs in. */
    public GrowwAuthenticator authenticator() {
        return authenticator.get();
    }

    /** The account's order book and fills. */
    public ExecutionDataClient brokerExecutionClient() {
        return executionClient.get();
    }

    /** The account's balance and holdings. */
    public AccountDataClient accountDataClient() {
        return accountDataClient.get();
    }

    public TradeRepository tradeRepository() {
        return tradeRepository;
    }

    public RealizedLotRepository realizedLotRepository() {
        return realizedLotRepository;
    }

    public AccountBalanceRepository accountBalanceRepository() {
        return accountBalanceRepository;
    }

    public TradeReasonRepository tradeReasonRepository() {
        return tradeReasonRepository;
    }

    public TradeNoteRepository tradeNoteRepository() {
        return tradeNoteRepository;
    }

    public TradeJournalService tradeJournalService() {
        return tradeJournalService;
    }

    public ReasonAttributor reasonAttributor() {
        return reasonAttributor;
    }

    public TradeNoteService tradeNoteService() {
        return tradeNoteService;
    }

    /** Pulls fills from the broker into this account's journal and brings what is derived from them up to date. */
    public BrokerFillSync brokerFillSync() {
        return new BrokerFillSync(tradeJournalService, tradeRepository, tradeReasonRepository, reasonAttributor);
    }

    @Override
    public String toString() {
        return "ProfileContext[" + profile.name() + (profile.primary() ? ", first" : "") + "]";
    }
}
